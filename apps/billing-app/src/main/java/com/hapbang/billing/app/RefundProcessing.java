package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointRefund;
import com.hapbang.billing.domain.PointRefundRequest;
import com.hapbang.billing.domain.PointRefundStatus;
import com.hapbang.billing.domain.RefundManualReviewReason;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.PointRefundRepository;
import com.hapbang.billing.out.PointRefundRequestRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 환불 처리의 DB 단계를 각각 짧은 트랜잭션으로 처리한다 (#260). TossPayments 취소·조회는 이 단계들 사이(트랜잭션 밖)에서 한다.
// - next: 다음에 할 일을 정한다. PG 취소 전이면 요청·개별 환불을 PROCESSING으로 바꿔 커밋한 뒤(요청 전 기록) 취소 대상을 돌려준다.
// - succeed: PG가 취소한 금액만 그 충전 건 배분에서 확정 차감하고 충전 건 환불 누계·요청 완료액을 늘린다.
// - fail: 실패가 확정된 충전 건 금액과 아직 PG에 요청하지 않은 뒤 순서 충전 건 금액을 반환하고 요청을 끝낸다(FIFO 중단).
// - markUnknown: 결과 불명확. 보류를 유지한다. requireManualReview: 운영자 확인(보류 유지, 요청하지 않은 건은 반환).
// - cancel: PG 취소 전(REQUESTED)인 요청을 회원이 취소한다(전부 반환).
// 락 순서: 지갑 → 환불 요청 → 보류 → 충전 건(보류·반환·확정 차감의 지갑 → 보류 → 충전 건 사이에 환불 요청을 둔다).
// 같은 환불 요청의 변경은 모두 환불 요청 락 아래에서 상태를 다시 읽고 판단하므로, API 요청과 결과 확인 배치가 겹쳐도
// 개별 환불 하나는 확정 차감 또는 반환 중 하나로 한 번만 처리된다(보류 상세 멱등키 CAPTURE·RELEASE:{holdId}:REFUND-{refundId}).
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundProcessing {

    static final String STOPPED_AFTER_FAILURE = "STOPPED_AFTER_PREVIOUS_FAILURE";
    static final String STOPPED_FOR_REVIEW = "STOPPED_FOR_MANUAL_REVIEW";
    static final String CANCELED_BY_MEMBER = "CANCELED_BY_MEMBER";

    // UNKNOWN 자동 복구 한도 (#260 확정 정책 ②): 처음 UNKNOWN이 된 뒤 24시간, 또는 결과 재확인 12회 중 먼저 도달한 것
    static final Duration UNKNOWN_TIME_LIMIT = Duration.ofHours(24);
    static final int UNKNOWN_CHECK_LIMIT = 12;
    // 같은 멱등키로 PG 취소를 다시 POST할 수 있는 안전 기간. TossPayments 멱등키는 "처음 사용한 날부터 15일" 유효한데, 날짜 기준으로
    // 세면 시각 기준 15일보다 일찍 끝날 수 있으므로 하루를 남기고 최초 요청 + 14일부터(경계 포함) 자동 POST를 막는다 (#260 검토 4)
    static final Duration IDEMPOTENCY_KEY_SAFE_WINDOW = Duration.ofDays(14);

    private final WalletRepository walletRepository;
    private final PointRefundRequestRepository pointRefundRequestRepository;
    private final PointRefundRepository pointRefundRepository;
    private final PointChargeRepository pointChargeRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final InternalHoldFacade internalHoldFacade;
    private final PaymentKeyCipher paymentKeyCipher;
    private final EntityManager entityManager;
    private final RefundClock refundClock;

    // 다음에 할 일을 정한다. 앞 순서 충전 건이 모두 성공해야 다음 충전 건을 취소한다(순차 FIFO).
    // - 끝났거나 운영자 확인 상태 → Stop
    // - 결과를 기다리는 건(PROCESSING·UNKNOWN)이 있으면: staleBefore가 있고(결과 확인) 그 건의 마지막 변경이 그 이전이면 결과 확인
    //   대상(Call RESOLVE)으로 돌려주고 변경 시각을 갱신해 다른 확인과 겹치지 않게 한다. 아니면 Stop(다른 요청이 처리 중이거나 다음 확인 때).
    //   UNKNOWN 자동 복구 한도(24시간·재확인 12회)에 이미 도달했으면 PG를 부르지 않고 운영자 확인(Review)으로 돌려준다
    // - PENDING → PROCESSING으로 바꿔 커밋하고 취소 대상(Call START)을 돌려준다. 결제키를 읽을 수 없으면 PENDING 그대로 START를
    //   돌려주고(결제키 null) 호출한 쪽이 PG 호출 없이 실패로 끝낸다.
    // - 모두 성공 → 요청 COMPLETED
    @Transactional
    public Step next(Long refundRequestId, Instant staleBefore) {
        PointRefundRequest request = lockRequest(refundRequestId);
        List<PointRefund> refunds = refundsOf(request);
        if (!request.isOpen()) {
            return new Step.Stop(RefundResult.of(request, refunds));
        }
        Instant now = now();
        for (PointRefund refund : refunds) {
            switch (refund.getStatus()) {
                case SUCCEEDED -> {
                    continue;
                }
                case PROCESSING, UNKNOWN -> {
                    if (staleBefore == null || !refund.getUpdatedAt().isBefore(staleBefore)) {
                        return new Step.Stop(RefundResult.of(request, refunds));
                    }
                    RefundManualReviewReason limit = refund.unknownLimitReached(refundClock.now(), UNKNOWN_TIME_LIMIT,
                            UNKNOWN_CHECK_LIMIT);
                    if (limit != null) {
                        // 운영자 확인 전환은 지갑 락이 필요해(뒤 순서 건 반환) 이 트랜잭션(요청 락만)에서 하지 않는다
                        return new Step.Review(refund.getId(), limit);
                    }
                    touch(request, refund, now);
                    return new Step.Call(CallKind.RESOLVE, target(refund, readablePaymentKey(refund)));
                }
                case PENDING -> {
                    String paymentKey = readablePaymentKey(refund);
                    if (paymentKey != null) {
                        request.startProcessing();
                        refund.startAttempt(refundClock.now());
                        pointRefundRequestRepository.touchUpdatedAt(request.getId(), now);
                    }
                    return new Step.Call(CallKind.START, target(refund, paymentKey));
                }
                // 요청이 끝나지 않았는데 앞 순서에 끝난 실패·취소·운영자 확인 건이 있으면 기록이 어긋난 것이다
                case FAILED, CANCELED, MANUAL_REVIEW_REQUIRED -> {
                    log.error("환불 요청과 개별 환불 상태가 맞지 않는다. 운영자 확인 필요: refundRequestId={}, refundId={}, status={}",
                            request.getId(), refund.getId(), refund.getStatus());
                    throw new BillingException(BillingErrorCode.REFUND_STATE_INCONSISTENT);
                }
            }
        }
        request.close(now, null);
        return new Step.Stop(RefundResult.of(request, refunds));
    }

    // 요청의 마지막 변경 시각만 갱신한다(처리 중 예외가 난 요청을 결과 확인 배치 목록 뒤로 보낸다, #260 2차).
    @Transactional
    public void touchRequest(Long refundRequestId) {
        pointRefundRequestRepository.touchUpdatedAt(refundRequestId, now());
    }

    // 결과 확인에서 취소 결과 조회를 시도하기 전에 재확인 횟수를 늘린다(조회가 실패해도 센다, 정책 ②). 결과를 기다리는 건이 아니면 false.
    @Transactional
    public boolean recordResultCheck(Long refundId) {
        PointRefund refund = lockedRefund(refundId);
        if (!refund.isInFlight()) {
            return false;
        }
        refund.recordResultCheck(refundClock.now());
        return true;
    }

    // 결과 확인 중 같은 멱등키로 PG 취소를 다시 POST해도 되는지 판단하고, 되면 요청 기록을 남긴다(요청 전에 커밋).
    // 최초 요청 + 안전 기간(14일)이 지났으면(경계 포함) POST하지 않는다(EXPIRED). 결과를 기다리는 건이 아니면 NOT_IN_FLIGHT.
    @Transactional
    public RetryDecision reserveRetry(Long refundId) {
        PointRefund refund = lockedRefund(refundId);
        if (!refund.isInFlight()) {
            return RetryDecision.NOT_IN_FLIGHT;
        }
        Instant now = refundClock.now();
        if (refund.isIdempotencyWindowOver(now, IDEMPOTENCY_KEY_SAFE_WINDOW)) {
            return RetryDecision.EXPIRED;
        }
        refund.recordCancelRetry(now);
        return RetryDecision.ALLOWED;
    }

    // PG 취소 성공. PG가 취소한 금액(canceledAmountHp)이 이 충전 건 취소 금액과 같으면 그 충전 건 배분에서 확정 차감하고(원장 REFUND)
    // 충전 건 환불 누계·요청 완료액을 늘린다. 이미 반영했으면 다시 바꾸지 않는다. 금액이 다르면 차감하지 않고 운영자 확인으로 둔다.
    @Transactional
    public void succeed(Long refundId, String transactionKey, long canceledAmountHp) {
        Locked locked = lock(refundId);
        PointRefund refund = locked.refund();
        if (refund.getStatus() == PointRefundStatus.SUCCEEDED) {
            return;
        }
        if (!refund.isInFlight()) {
            // 반환을 마친 건(FAILED·CANCELED)에서 PG 취소 성공이 확인되면 HP와 현금이 함께 돌아간 것이다
            log.error("반환한 환불 건의 PG 취소 성공이 확인되었다. 운영자 확인 필요: refundRequestId={}, refundId={}, status={}",
                    locked.request().getId(), refundId, refund.getStatus());
            throw new BillingException(BillingErrorCode.REFUND_STATE_INCONSISTENT);
        }
        if (canceledAmountHp != refund.getRefundAmountHp()) {
            log.error("PG 취소 금액이 환불 건 금액과 다르다. 차감하지 않고 운영자 확인: refundRequestId={}, refundId={}, "
                    + "expected={}, canceled={}", locked.request().getId(), refundId, refund.getRefundAmountHp(),
                    canceledAmountHp);
            requireManualReview(locked, RefundManualReviewReason.CANCEL_AMOUNT_MISMATCH,
                    "CANCEL_AMOUNT_MISMATCH:" + canceledAmountHp);
            return;
        }
        PointRefundRequest request = locked.request();
        internalHoldFacade.capture(CaptureCommand.ofCharge(locked.wallet().getUserId(),
                BillingReferenceType.POINT_REFUND_REQUEST, request.getId(),
                BillingKeys.refundHoldRequestId(refundId), refund.getChargeId(), refund.getRefundAmountHp()));
        // 지갑 → 환불 요청 → 보류(확정 차감) 다음에 충전 건을 잠근다
        PointCharge charge = lockCharge(refund.getChargeId());
        charge.refund(refund.getRefundAmountHp());
        refund.succeed(transactionKey, now());
        request.addCompleted(refund.getRefundAmountHp());
    }

    // PG 취소 실패 확정(또는 결제키를 읽을 수 없어 PG에 요청하지 못함). 이 충전 건 금액을 반환하고, 아직 PG에 요청하지 않은
    // 뒤 순서 충전 건도 반환(CANCELED)한 뒤 요청을 끝낸다(앞서 성공한 금액이 있으면 PARTIALLY_COMPLETED, 없으면 FAILED).
    @Transactional
    public void fail(Long refundId, String reason) {
        Locked locked = lock(refundId);
        PointRefund refund = locked.refund();
        if (!refund.isInFlight() && refund.getStatus() != PointRefundStatus.PENDING) {
            return;
        }
        Instant now = now();
        release(locked, refund);
        refund.fail(reason, now);
        stopPending(locked, STOPPED_AFTER_FAILURE, now);
        closeIfSettled(locked, reason, now);
    }

    // 결과 불명확. 보류를 유지하고 사유를 남긴다(결과 확인 배치가 다시 확인한다). 변경 시각을 갱신한다.
    // 정책 ②: UNKNOWN 자동 복구 한도(24시간·재확인 12회)에 도달했거나, 멱등키 안전 기간이 지나 같은 키로 다시 요청할 수 없으면
    // 보류를 유지한 채 운영자 확인으로 바꾼다(HP 자동 반환 없음). 사유에 원인을 구분해 남긴다.
    @Transactional
    public void markUnknown(Long refundId, String reason) {
        Locked locked = lock(refundId);
        PointRefund refund = locked.refund();
        if (!refund.isInFlight()) {
            return;
        }
        Instant now = refundClock.now();
        refund.markUnknown(reason, now);
        RefundManualReviewReason limit = refund.unknownLimitReached(now, UNKNOWN_TIME_LIMIT, UNKNOWN_CHECK_LIMIT);
        if (limit == null && refund.isIdempotencyWindowOver(now, IDEMPOTENCY_KEY_SAFE_WINDOW)) {
            limit = RefundManualReviewReason.IDEMPOTENCY_KEY_EXPIRED;
        }
        if (limit != null) {
            requireManualReview(locked, limit, reason + " / " + limitReason(limit, refund));
            return;
        }
        touch(locked.request(), refund, now());
    }

    // 운영자 확인이 필요하다(PG 결과와 기록 불일치·PG 환급 불가·자동 복구 한도). 이 충전 건 금액은 보류로 남기고(반환·확정 차감 없음),
    // 아직 PG에 요청하지 않은 뒤 순서 건은 반환(CANCELED)한다.
    @Transactional
    public void requireManualReview(Long refundId, RefundManualReviewReason reviewReason, String reason) {
        Locked locked = lock(refundId);
        if (locked.refund().isInFlight()) {
            requireManualReview(locked, reviewReason,
                    reason == null ? limitReason(reviewReason, locked.refund()) : reason);
        }
    }

    // PG 취소 전(REQUESTED)인 요청을 회원이 취소한다. 첫 PG 취소 전에 요청을 PROCESSING으로 커밋하므로(같은 요청 락) PG 취소와 겹치지 않는다.
    // 다른 회원의 요청은 없는 요청과 같게(REFUND_NOT_FOUND) 본다.
    @Transactional
    public RefundResult cancel(Long userId, Long refundRequestId) {
        Long walletId = walletIdOf(userId);
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId);
        PointRefundRequest request = pointRefundRequestRepository.findByIdForUpdate(refundRequestId)
                .filter(found -> found.getWalletId().equals(walletId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.REFUND_NOT_FOUND));
        entityManager.refresh(request);
        List<PointRefund> refunds = refundsOf(request);
        Instant now = now();
        request.cancel(now);
        Locked locked = new Locked(wallet, request, null, refunds);
        for (PointRefund refund : refunds) {
            release(locked, refund);
            refund.cancel(CANCELED_BY_MEMBER, now);
        }
        return RefundResult.of(request, refunds);
    }

    // 회원 자신의 환불 요청 결과. 다른 회원의 요청은 없는 요청과 같게 본다.
    @Transactional(readOnly = true)
    public RefundResult resultOf(Long userId, Long refundRequestId) {
        Long walletId = walletIdOf(userId);
        PointRefundRequest request = pointRefundRequestRepository.findById(refundRequestId)
                .filter(found -> found.getWalletId().equals(walletId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.REFUND_NOT_FOUND));
        return RefundResult.of(request, refundsOf(request));
    }

    // 환불 요청의 현재 결과
    @Transactional(readOnly = true)
    public RefundResult result(Long refundRequestId) {
        PointRefundRequest request = pointRefundRequestRepository.findById(refundRequestId).orElseThrow();
        return RefundResult.of(request, refundsOf(request));
    }

    private void requireManualReview(Locked locked, RefundManualReviewReason reviewReason, String reason) {
        Instant now = now();
        locked.refund().requireManualReview(reviewReason, reason, refundClock.now());
        stopPending(locked, STOPPED_FOR_REVIEW, now);
        if (locked.request().isOpen()) {
            locked.request().requireManualReview(reason);
        }
    }

    // 아직 PG에 요청하지 않은 충전 건(PENDING)을 반환하고 CANCELED로 끝낸다.
    private void stopPending(Locked locked, String reason, Instant now) {
        for (PointRefund pending : locked.refunds()) {
            if (pending.getStatus() == PointRefundStatus.PENDING && !pending.getId().equals(locked.refund().getId())) {
                release(locked, pending);
                pending.cancel(reason, now);
            }
        }
    }

    // 결과를 기다리는 건이 남아 있지 않으면 요청을 끝낸다(순차 처리라 정상이면 남아 있지 않다).
    private static void closeIfSettled(Locked locked, String reason, Instant now) {
        boolean inFlight = locked.refunds().stream().anyMatch(PointRefund::isInFlight);
        if (!inFlight && locked.request().isOpen()) {
            locked.request().close(now, reason);
        }
    }

    // 충전 건 하나의 배분에서 그 금액만 반환한다(반환 요청 ID REFUND-{refundId}, 충전 건 원금 복원).
    private void release(Locked locked, PointRefund refund) {
        internalHoldFacade.release(InternalReleaseCommand.ofCharge(locked.wallet().getUserId(),
                BillingReferenceType.POINT_REFUND_REQUEST, locked.request().getId(),
                BillingKeys.refundHoldRequestId(refund.getId()), refund.getChargeId(), refund.getRefundAmountHp()));
    }

    // 지갑 → 환불 요청 순서로 잠그고 개별 환불·같은 요청의 개별 환불 목록을 최신 상태로 읽는다.
    private Locked lock(Long refundId) {
        PointRefund found = pointRefundRepository.findById(refundId).orElseThrow();
        Wallet wallet = ledgerRecordUseCase.lockWallet(found.getWalletId());
        PointRefundRequest request = lockRequest(found.getRefundRequest().getId());
        List<PointRefund> refunds = refundsOf(request);
        PointRefund refund = refunds.stream().filter(candidate -> candidate.getId().equals(refundId)).findFirst()
                .orElseThrow();
        return new Locked(wallet, request, refund, refunds);
    }

    // 자동 복구 한도·멱등키 만료로 운영자 확인이 될 때 사유 문구
    private static String limitReason(RefundManualReviewReason reviewReason, PointRefund refund) {
        return switch (reviewReason) {
            case UNKNOWN_TIME_LIMIT -> "UNKNOWN_TIME_LIMIT: UNKNOWN since " + refund.getUnknownSince() + ", limit "
                    + UNKNOWN_TIME_LIMIT;
            case UNKNOWN_CHECK_LIMIT -> "UNKNOWN_CHECK_LIMIT: result checks " + refund.getResultCheckCount() + "/"
                    + UNKNOWN_CHECK_LIMIT;
            case IDEMPOTENCY_KEY_EXPIRED -> "IDEMPOTENCY_KEY_EXPIRED: first cancel request "
                    + refund.getFirstCancelRequestedAt() + ", safe window " + IDEMPOTENCY_KEY_SAFE_WINDOW;
            default -> reviewReason.name();
        };
    }

    // 요청 락 아래에서 개별 환불 하나를 최신 상태로 읽는다(지갑 락 없이 요청 락만 잡는 단계용).
    private PointRefund lockedRefund(Long refundId) {
        PointRefund refund = pointRefundRepository.findById(refundId).orElseThrow();
        lockRequest(refund.getRefundRequest().getId());
        entityManager.refresh(refund);
        return refund;
    }

    private PointRefundRequest lockRequest(Long refundRequestId) {
        PointRefundRequest request = pointRefundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new IllegalStateException("환불 요청이 없습니다. refundRequestId=" + refundRequestId));
        // 락 조회는 이미 영속성 컨텍스트에 있던 엔티티를 바꾸지 않으므로 락을 잡은 뒤 최신 상태로 다시 읽는다
        entityManager.refresh(request);
        return request;
    }

    // 요청 락 아래에서 개별 환불을 최신 상태로 읽는다(FIFO 순서 = ID 오름차순).
    private List<PointRefund> refundsOf(PointRefundRequest request) {
        List<PointRefund> refunds = pointRefundRepository.findAllByRefundRequestIdOrderByIdAsc(request.getId());
        refunds.forEach(entityManager::refresh);
        return refunds;
    }

    private PointCharge lockCharge(Long chargeId) {
        PointCharge charge = pointChargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new IllegalStateException("충전 건이 없습니다. chargeId=" + chargeId));
        entityManager.refresh(charge);
        return charge;
    }

    private void touch(PointRefundRequest request, PointRefund refund, Instant now) {
        pointRefundRepository.touchUpdatedAt(refund.getId(), now);
        pointRefundRequestRepository.touchUpdatedAt(request.getId(), now);
    }

    private Long walletIdOf(Long userId) {
        return walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.REFUND_NOT_FOUND));
    }

    // 충전 건의 결제키 원문. 복호화할 수 없으면(암호화 키 교체 등) null이다.
    private String readablePaymentKey(PointRefund refund) {
        PointCharge charge = pointChargeRepository.findById(refund.getChargeId()).orElseThrow();
        try {
            return paymentKeyCipher.decrypt(charge.getPgPaymentKey());
        } catch (IllegalStateException exception) {
            log.error("결제키 암호문을 복호화할 수 없다: refundId={}, chargeId={}", refund.getId(), charge.getId());
            return null;
        }
    }

    private static CancelTarget target(PointRefund refund, String paymentKey) {
        return new CancelTarget(refund.getId(), refund.getRefundAmountHp(), refund.getIdempotencyKey(), paymentKey);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private record Locked(Wallet wallet, PointRefundRequest request, PointRefund refund, List<PointRefund> refunds) {
    }

    // START: 처음 PG 취소, RESOLVE: 결과를 기다리는 건의 결과 확인(조회 → 우리 취소가 없으면 같은 멱등키 재요청)
    public enum CallKind {
        START, RESOLVE
    }

    // 같은 멱등키 재요청 가능 여부: 가능(요청 기록함), 안전 기간 지남, 결과를 기다리는 건이 아님
    public enum RetryDecision {
        ALLOWED, EXPIRED, NOT_IN_FLIGHT
    }

    // 다음 할 일: PG 호출(Call), 자동 복구 한도 도달로 운영자 확인 전환(Review), 멈춤(Stop: 끝남·운영자 확인·결과 대기)
    public sealed interface Step {
        record Call(CallKind kind, CancelTarget target) implements Step {
        }

        record Review(Long refundId, RefundManualReviewReason reason) implements Step {
        }

        record Stop(RefundResult result) implements Step {
        }
    }

    // PG 취소 대상. paymentKey는 원문이며 복호화할 수 없으면 null이다.
    public record CancelTarget(Long refundId, long amountHp, String idempotencyKey, String paymentKey) {

        // 결제키 원문이 로그에 찍히지 않게 한다
        @Override
        public String toString() {
            return "CancelTarget[refundId=" + refundId + ", amountHp=" + amountHp + ", idempotencyKey="
                    + idempotencyKey + "]";
        }
    }
}
