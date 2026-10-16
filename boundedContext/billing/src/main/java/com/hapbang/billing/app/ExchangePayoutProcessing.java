package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.ExchangePayout;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.out.ExchangeAccountRepository;
import com.hapbang.billing.out.ExchangePayoutRepository;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.billing.out.PayoutTransferResult;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

// 환전 송금의 DB 단계 (#304). 메서드마다 짧은 트랜잭션이고 Mock 은행 HTTP는 부르지 않는다(ExchangePayoutUseCase가 트랜잭션 밖에서 부른다).
// 잠금 순서는 지갑 → 환전 → (보류 | 송금 시도)로 관리자 승인·거절·회원 취소와 같다(ExchangeLocks). 같은 환전의 송금 실행·자동 결과 확인·
// 관리자 처리(재확인·재송금·최종 실패)가 동시에 와도 지갑 락에서 차례가 정해지고, 뒤쪽은 바뀐 상태를 보고 새로 보내거나 겹쳐 처리하지 않는다.
// - start: 보낼지(새 시도 저장·커밋), 자동 결과 확인을 할지(확인 시각·횟수 기록·커밋), 기한이 지나 운영자 확인으로 넘길지 정한다
// - apply: Mock 은행 응답을 반영한다. 성공이면 같은 트랜잭션에서 보류 전액 확정 차감
// - failFinally: 관리자 최종 실패 확정. 같은 트랜잭션에서 보류 전액 반환
// 확정 차감(성공)은 결과 대기·운영자 확인 시도에서만, 반환(최종 실패)은 FAILED 환전에서만 하므로 환전 락 아래에서 둘 중 하나만 된다.
@Slf4j
@Service
public class ExchangePayoutProcessing {

    static final Set<ExchangePayoutStatus> IN_FLIGHT =
            EnumSet.of(ExchangePayoutStatus.PROCESSING, ExchangePayoutStatus.UNKNOWN);
    static final String AUTO_CHECK_EXPIRED = "AUTO_CHECK_EXPIRED";
    static final String BANK_RECORD_NOT_FOUND = "BANK_RECORD_NOT_FOUND";

    private final ExchangeLocks exchangeLocks;
    private final ExchangeRepository exchangeRepository;
    private final ExchangeAccountRepository exchangeAccountRepository;
    private final ExchangePayoutRepository exchangePayoutRepository;
    private final InternalHoldFacade internalHoldFacade;
    private final ExchangeAccountCipher exchangeAccountCipher;
    private final ExchangePayoutClock clock;
    private final ExchangePayoutRequestIds requestIds;
    private final EntityManager entityManager;
    private final Duration minimumAge;
    private final Duration autoCheckLimit;

    public ExchangePayoutProcessing(ExchangeLocks exchangeLocks, ExchangeRepository exchangeRepository,
            ExchangeAccountRepository exchangeAccountRepository, ExchangePayoutRepository exchangePayoutRepository,
            InternalHoldFacade internalHoldFacade, ExchangeAccountCipher exchangeAccountCipher,
            ExchangePayoutClock clock, ExchangePayoutRequestIds requestIds, EntityManager entityManager,
            @Value("${hapbang.billing.exchange.payout.recovery-after:PT1M}") Duration minimumAge,
            @Value("${hapbang.billing.exchange.payout.auto-check-limit:PT24H}") Duration autoCheckLimit) {
        this.exchangeLocks = exchangeLocks;
        this.exchangeRepository = exchangeRepository;
        this.exchangeAccountRepository = exchangeAccountRepository;
        this.exchangePayoutRepository = exchangePayoutRepository;
        this.internalHoldFacade = internalHoldFacade;
        this.exchangeAccountCipher = exchangeAccountCipher;
        this.clock = clock;
        this.requestIds = requestIds;
        this.entityManager = entityManager;
        this.minimumAge = minimumAge;
        this.autoCheckLimit = autoCheckLimit;
    }

    // 송금 실행·자동 결과 확인의 다음 단계 (자동 경로: 정산 실행·결과 확인. 확정 실패한 환전을 다시 보내지 않는다).
    // - APPROVED: 정산 예정 시각이 지났고 보류가 그대로면 새 시도(PROCESSING)를 저장하고 Send
    // - PROCESSING·UNKNOWN: 마지막 시도가 결과 대기면 checkedBefore 이전에 마지막으로 보냈거나 확인한 경우만 처리한다.
    //   최초 송금 요청 후 자동 확인 기한(기본 24시간)이 지났으면 은행을 부르지 않고 운영자 확인으로 넘긴다(보류 유지, 실패로 보지 않음).
    //   아니면 확인 시각·횟수를 기록하고 Check. 운영자 확인 대기면 Stop
    // - COMPLETED·FAILED·FINAL_FAILED: Stop (FAILED는 관리자만 다시 보낼 수 있다)
    // - REQUESTED·REJECTED·CANCELED: EXCHANGE_NOT_PAYABLE
    @Transactional
    public Step start(Long exchangeId, Instant checkedBefore) {
        Exchange exchange = exchangeLocks.lock(exchangeId);
        ExchangePayout latest = latestOf(exchangeId);
        Instant now = clock.now();
        return switch (exchange.getStatus()) {
            case APPROVED -> {
                if (latest != null) {
                    throw inconsistent(exchange, "승인 상태인데 송금 시도가 있다 attemptNo=" + latest.getAttemptNo());
                }
                yield send(exchange, 1, now, null);
            }
            case PROCESSING, UNKNOWN -> check(exchange, latest, checkedBefore, now);
            case COMPLETED, FAILED, FINAL_FAILED -> new Step.Stop();
            case REQUESTED, REJECTED, CANCELED -> throw new BillingException(BillingErrorCode.EXCHANGE_NOT_PAYABLE);
        };
    }

    // 관리자 수동 재송금: 마지막 시도가 확정 실패(FAILED)인 환전만 새 시도(새 지급 요청 ID, 보낸 관리자 기록)로 보낸다.
    // 결과 대기·운영자 확인·성공·최종 실패·그 밖의 상태는 EXCHANGE_PAYOUT_NOT_RETRYABLE(성공 여부가 불확실하면 절대 다시 보내지 않는다)
    @Transactional
    public Step startRetry(Long adminId, Long exchangeId) {
        Exchange exchange = exchangeLocks.lock(exchangeId);
        if (exchange.getStatus() != ExchangeStatus.FAILED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_NOT_RETRYABLE);
        }
        ExchangePayout latest = latestOf(exchangeId);
        if (latest == null || latest.getStatus() != ExchangePayoutStatus.FAILED) {
            throw inconsistent(exchange, "실패 상태인데 마지막 시도가 실패가 아니다");
        }
        log.info("환전 송금 관리자 재송금: exchangeId={}, adminId={}, previousAttemptNo={}", exchangeId, adminId,
                latest.getAttemptNo());
        return send(exchange, latest.getAttemptNo() + 1, clock.now(), adminId);
    }

    // 관리자 결과 재확인 준비: 결과가 확정되지 않은 마지막 시도(PROCESSING·UNKNOWN·MANUAL_REVIEW_REQUIRED)의 지급 요청 ID.
    // 재확인한 관리자·시각을 남긴다(자동 확인 시각·횟수는 바꾸지 않는다). 그 밖은 EXCHANGE_PAYOUT_NOT_CHECKABLE
    @Transactional
    public String startManualCheck(Long adminId, Long exchangeId) {
        Exchange exchange = exchangeLocks.lock(exchangeId);
        ExchangePayout latest = latestOf(exchangeId);
        if (!exchange.isPayoutInFlight() || latest == null || !latest.isUnresolved()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_NOT_CHECKABLE);
        }
        latest.recordManualCheck(adminId, clock.now());
        log.info("환전 송금 관리자 결과 재확인: exchangeId={}, adminId={}, attemptNo={}, payoutRequestId={}", exchangeId,
                adminId, latest.getAttemptNo(), latest.getPayoutRequestId());
        return latest.getPayoutRequestId();
    }

    // Mock 은행 응답을 반영한다(자동 경로). 시도가 이미 끝났거나 운영자 확인 대기면 바꾸지 않는다.
    // - Succeeded: 금액·거래 번호가 맞으면 보류 전액 확정 차감 + 시도·환전 COMPLETED. 맞지 않으면 운영자 확인(차감 없음)
    // - Failed: 시도·환전 FAILED. 보류는 반환하지 않는다(관리자가 재송금 또는 최종 실패를 정한다)
    // - Processing: PROCESSING, Unknown·NotFound(재전송 못 함): UNKNOWN. 보류 유지
    // - Conflict·Rejected: 시도 운영자 확인, 환전 UNKNOWN. 보류 유지
    @Transactional
    public void apply(Long exchangeId, String payoutRequestId, PayoutTransferResult result) {
        Locked locked = lockPayout(exchangeId, payoutRequestId);
        Exchange exchange = locked.exchange();
        ExchangePayout payout = locked.payout();
        if (!payout.isInFlight()) {
            alreadyApplied(exchange, payout, result);
            return;
        }
        Instant now = clock.now();
        switch (result) {
            case PayoutTransferResult.Succeeded succeeded -> succeed(exchange, payout, succeeded, now);
            case PayoutTransferResult.Failed failed -> fail(exchange, payout, failed.failureCode(), now);
            case PayoutTransferResult.Processing ignored -> {
                payout.processing();
                exchange.payoutProcessing();
            }
            case PayoutTransferResult.Unknown unknown -> unknown(exchange, payout, unknown.reason());
            case PayoutTransferResult.NotFound ignored -> unknown(exchange, payout, "NOT_FOUND");
            case PayoutTransferResult.Conflict conflict ->
                    manualReview(exchange, payout, "CONFLICT:" + conflict.code(), now);
            case PayoutTransferResult.Rejected rejected ->
                    manualReview(exchange, payout, "REJECTED_" + rejected.httpStatus() + ":" + rejected.code(), now);
        }
    }

    // 관리자 결과 재확인 응답을 반영한다. 결과 대기·운영자 확인 시도 모두 받는다. 새로 보내지 않는다.
    // - Succeeded(일치) → 확정 차감 + COMPLETED, Failed → FAILED(보류 유지), Conflict·Rejected·성공 불일치 → 운영자 확인
    // - Processing·Unknown → 그대로(운영자 확인이면 운영자 확인 유지, 결과 대기면 PROCESSING·UNKNOWN)
    // - NotFound: 운영자 확인 시도이고 마지막 송금·자동 확인 후 일정 시간(기본 1분)이 지났으면 은행이 이 지급 요청 ID를 받지 않은 것으로
    //   확인된 것이라 FAILED(BANK_RECORD_NOT_FOUND)로 확정한다(이 시도는 더 이상 자동으로 보내지 않는다). 결과 대기 시도는 자동 결과
    //   확인이 같은 ID로 다시 보낼 수 있으므로 UNKNOWN으로 둔다
    @Transactional
    public void applyManualCheck(Long exchangeId, String payoutRequestId, PayoutTransferResult result) {
        Locked locked = lockPayout(exchangeId, payoutRequestId);
        Exchange exchange = locked.exchange();
        ExchangePayout payout = locked.payout();
        if (!payout.isUnresolved()) {
            alreadyApplied(exchange, payout, result);
            return;
        }
        boolean manual = payout.getStatus() == ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED;
        Instant now = clock.now();
        switch (result) {
            case PayoutTransferResult.Succeeded succeeded -> succeed(exchange, payout, succeeded, now);
            case PayoutTransferResult.Failed failed -> fail(exchange, payout, failed.failureCode(), now);
            case PayoutTransferResult.Processing ignored -> {
                if (!manual) {
                    payout.processing();
                    exchange.payoutProcessing();
                }
            }
            case PayoutTransferResult.Unknown unknown -> {
                if (!manual) {
                    unknown(exchange, payout, unknown.reason());
                }
            }
            case PayoutTransferResult.NotFound ignored -> {
                if (manual && !payout.getLastCheckedAt().isAfter(now.minus(minimumAge))) {
                    fail(exchange, payout, BANK_RECORD_NOT_FOUND, now);
                } else if (!manual) {
                    unknown(exchange, payout, "NOT_FOUND");
                }
            }
            case PayoutTransferResult.Conflict conflict ->
                    manualReview(exchange, payout, "CONFLICT:" + conflict.code(), now);
            case PayoutTransferResult.Rejected rejected ->
                    manualReview(exchange, payout, "REJECTED_" + rejected.httpStatus() + ":" + rejected.code(), now);
        }
    }

    // 관리자 최종 실패 확정 (한 트랜잭션): 마지막 시도가 은행 확정 실패(FAILED)인 환전만 FINAL_FAILED로 바꾸고 보류한 수익 HP 전부를
    // 신청자에게 반환한다(기존 반환 로직, 반환 요청 ID EXCHANGE-PAYOUT-FAIL-{환전 ID}, 수익 HP → AVAILABLE). 결과 대기·운영자 확인
    // (성공 가능성이 남음)·성공·그 밖의 상태는 EXCHANGE_PAYOUT_NOT_FINALIZABLE. 같은 관리자·같은 사유 재요청은 처음 결과(다시 반환 안 함)
    @Transactional
    public void failFinally(Long adminId, Long exchangeId, String reason) {
        String normalized = Exchange.normalizeFinalFailureReason(reason);
        Exchange exchange = exchangeLocks.lock(exchangeId);
        if (exchange.isFinalFailedBy(adminId, normalized)) {
            return;
        }
        if (exchange.getStatus() != ExchangeStatus.FAILED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_NOT_FINALIZABLE);
        }
        ExchangePayout latest = latestOf(exchangeId);
        if (latest == null || latest.getStatus() != ExchangePayoutStatus.FAILED) {
            throw inconsistent(exchange, "실패 상태인데 마지막 시도가 실패가 아니다");
        }
        exchangeLocks.requireFullyHeld(exchange);
        exchange.failFinally(adminId, normalized, clock.now());
        ReleaseResult released = internalHoldFacade.release(InternalReleaseCommand.full(exchange.getUserId(),
                BillingReferenceType.EXCHANGE, exchangeId, BillingKeys.exchangePayoutFailRequestId(exchangeId)));
        if (released.replayed() || released.releasedRevenueHp() != exchange.getRequestedAmountHp()
                || released.releasedChargeHp() != 0 || released.remainingHeldHp() != 0) {
            throw inconsistent(exchange, "최종 실패 반환 결과 불일치 releasedRevenue=" + released.releasedRevenueHp()
                    + ", replayed=" + released.replayed());
        }
        log.info("환전 최종 실패 확정·보류 반환: exchangeId={}, adminId={}, attempts={}, releasedHp={}", exchangeId, adminId,
                latest.getAttemptNo(), released.releasedRevenueHp());
    }

    // 실행이 끝난 시점의 환전·마지막 시도
    @Transactional(readOnly = true)
    public ExchangePayoutResult result(Long exchangeId, ExchangePayoutAction action) {
        Exchange exchange = exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND));
        return ExchangePayoutResult.of(exchange, latestOf(exchangeId), action);
    }

    // 자동 결과 확인 대상 환전 ID: 결과 대기 시도(PROCESSING·UNKNOWN) 중 마지막 송금·확인이 checkedBefore 이전인 것, 오래된 순
    @Transactional(readOnly = true)
    public List<Long> staleExchangeIds(Instant checkedBefore, int limit) {
        return exchangePayoutRepository.findExchangeIdsByStatusInCheckedBefore(IN_FLIGHT, checkedBefore,
                PageRequest.of(0, limit));
    }

    // 관리자 조회: 상태별 송금 시도(운영자 확인 대상 등), 오래된 순
    @Transactional(readOnly = true)
    public Page<AdminExchangePayoutResult> attempts(ExchangePayoutStatus status, Pageable pageable) {
        return exchangePayoutRepository.findAllByStatusOrderByIdAsc(status, pageable)
                .map(AdminExchangePayoutResult::of);
    }

    // 관리자 조회: 환전의 송금 시도 전부(시도 번호 순)
    @Transactional(readOnly = true)
    public List<AdminExchangePayoutResult> attemptsOf(Long exchangeId) {
        if (exchangeRepository.findById(exchangeId).isEmpty()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND);
        }
        return exchangePayoutRepository.findAllByExchangeIdOrderByAttemptNoAsc(exchangeId).stream()
                .map(AdminExchangePayoutResult::of).toList();
    }

    // 새 송금 시도: 정산 예정 시각·보류 확인 → 계좌번호 복호화(실패하면 시도를 만들지 않는다) → 시도 PROCESSING 저장 → 환전 PROCESSING
    private Step send(Exchange exchange, int attemptNo, Instant now, Long requestedBy) {
        if (!exchange.isPayoutDue(now)) {
            throw new BillingException(BillingErrorCode.EXCHANGE_PAYOUT_NOT_DUE);
        }
        exchangeLocks.requireFullyHeld(exchange);
        ExchangeAccount account = accountOf(exchange);
        String accountNumber = exchangeAccountCipher.decrypt(exchange.getId(), account.getAccountNumberEncrypted());
        ExchangePayout payout = exchangePayoutRepository.save(
                ExchangePayout.requested(exchange, attemptNo, requestIds.next(), now, requestedBy));
        exchange.startPayout();
        log.info("환전 송금 시도 저장: exchangeId={}, attemptNo={}, payoutRequestId={}, amountKrw={}, requestedBy={}",
                exchange.getId(), attemptNo, payout.getPayoutRequestId(), payout.getAmountKrw(), requestedBy);
        return new Step.Send(request(exchange, payout, account, accountNumber));
    }

    // 결과 대기 시도의 자동 결과 확인. 다른 실행이 방금 보냈거나 확인 중(마지막 확인이 checkedBefore 뒤)이면 건너뛴다.
    // 자동 확인 기한이 지났으면 운영자 확인으로 넘긴다. 은행에 기록이 없을 때 같은 내용으로 다시 보낼 요청도 만든다
    // (계좌번호를 복호화하지 못하면 재전송 없이 확인만 한다).
    private Step check(Exchange exchange, ExchangePayout latest, Instant checkedBefore, Instant now) {
        if (latest == null) {
            throw inconsistent(exchange, "결과 대기 상태인데 송금 시도가 없다");
        }
        if (latest.getStatus() == ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED) {
            return new Step.Stop();
        }
        if (!latest.isInFlight()) {
            throw inconsistent(exchange, "결과 대기 상태인데 마지막 시도가 " + latest.getStatus());
        }
        if (latest.getLastCheckedAt().isAfter(checkedBefore)) {
            return new Step.Stop();
        }
        if (latest.isAutoCheckExpired(now, autoCheckLimit)) {
            manualReview(exchange, latest, AUTO_CHECK_EXPIRED, now);
            return new Step.Stop();
        }
        latest.recordAutoCheck(now);
        PayoutTransferRequest resend = null;
        try {
            ExchangeAccount account = accountOf(exchange);
            resend = request(exchange, latest, account,
                    exchangeAccountCipher.decrypt(exchange.getId(), account.getAccountNumberEncrypted()));
        } catch (BillingException unreadable) {
            log.warn("환전 계좌번호를 읽지 못해 결과 확인만 한다(재전송 없음): exchangeId={}, payoutRequestId={}",
                    exchange.getId(), latest.getPayoutRequestId());
        }
        return new Step.Check(latest.getPayoutRequestId(), resend);
    }

    private Locked lockPayout(Long exchangeId, String payoutRequestId) {
        Exchange exchange = exchangeLocks.lock(exchangeId);
        ExchangePayout payout = exchangePayoutRepository.findByPayoutRequestIdForUpdate(payoutRequestId)
                .orElseThrow(() -> inconsistent(exchange, "송금 시도 없음 payoutRequestId=" + payoutRequestId));
        entityManager.refresh(payout);
        if (!payout.getExchange().getId().equals(exchangeId)) {
            throw inconsistent(exchange, "다른 환전의 송금 시도 payoutRequestId=" + payoutRequestId);
        }
        if (payout.isUnresolved() && !exchange.isPayoutInFlight()) {
            throw inconsistent(exchange, "결과 미확정 시도가 있는데 환전이 결과 대기가 아니다 payoutRequestId=" + payoutRequestId);
        }
        return new Locked(exchange, payout);
    }

    private void succeed(Exchange exchange, ExchangePayout payout, PayoutTransferResult.Succeeded succeeded,
            Instant now) {
        if (succeeded.amountKrw() != payout.getAmountKrw()) {
            manualReview(exchange, payout, "AMOUNT_MISMATCH:" + succeeded.amountKrw(), now);
            return;
        }
        if (succeeded.transactionId() == null || succeeded.transactionId().isBlank()) {
            manualReview(exchange, payout, "SUCCEEDED_WITHOUT_TRANSACTION", now);
            return;
        }
        exchangeLocks.requireFullyHeld(exchange);
        CaptureResult captured = internalHoldFacade.capture(CaptureCommand.full(exchange.getUserId(),
                BillingReferenceType.EXCHANGE, exchange.getId(),
                BillingKeys.exchangePayoutCaptureRequestId(exchange.getId())));
        if (captured.replayed() || captured.capturedRevenueHp() != exchange.getRequestedAmountHp()
                || captured.capturedChargeHp() != 0 || captured.remainingHeldHp() != 0) {
            throw inconsistent(exchange, "확정 차감 결과 불일치 capturedRevenue=" + captured.capturedRevenueHp()
                    + ", replayed=" + captured.replayed());
        }
        Instant completedAt = succeeded.completedAt() == null ? now
                : succeeded.completedAt().truncatedTo(ChronoUnit.MICROS);
        payout.succeed(succeeded.transactionId(), completedAt);
        exchange.completePayout(now);
        log.info("환전 송금 성공·확정 차감: exchangeId={}, attemptNo={}, payoutRequestId={}, capturedHp={}",
                exchange.getId(), payout.getAttemptNo(), payout.getPayoutRequestId(), captured.capturedRevenueHp());
    }

    // 시도 확정 실패. 보류는 반환하지 않는다(관리자가 재송금 또는 최종 실패를 정한다)
    private void fail(Exchange exchange, ExchangePayout payout, String failureCode, Instant now) {
        String code = failureCode == null ? "UNKNOWN" : failureCode;
        payout.fail(code, now);
        exchange.failPayout("MOCK_PAYOUT_FAILED:" + code, now);
        log.warn("환전 송금 시도 확정 실패(보류 유지, 관리자 판단 대기): exchangeId={}, attemptNo={}, payoutRequestId={}, code={}",
                exchange.getId(), payout.getAttemptNo(), payout.getPayoutRequestId(), code);
    }

    private void unknown(Exchange exchange, ExchangePayout payout, String reason) {
        payout.unknown(reason);
        exchange.payoutUnknown();
        log.warn("환전 송금 결과 불명(보류 유지, 같은 ID로 다시 확인): exchangeId={}, attemptNo={}, payoutRequestId={}, reason={}",
                exchange.getId(), payout.getAttemptNo(), payout.getPayoutRequestId(), reason);
    }

    private void manualReview(Exchange exchange, ExchangePayout payout, String reason, Instant now) {
        payout.requireManualReview(reason, now);
        exchange.payoutUnknown();
        log.error("환전 송금 운영자 확인 필요(보류 유지, 자동 처리 중단): exchangeId={}, attemptNo={}, payoutRequestId={}, reason={}, "
                + "autoChecks={}", exchange.getId(), payout.getAttemptNo(), payout.getPayoutRequestId(), reason,
                payout.getResultCheckCount());
    }

    // 이미 끝난 시도(또는 자동 경로에서 운영자 확인 대기 시도)에 온 응답. 바꾸지 않는다. 끝난 결과와 다르면 오류 로그만 남긴다
    // (은행 확정 결과는 바뀌지 않는다).
    private static void alreadyApplied(Exchange exchange, ExchangePayout payout, PayoutTransferResult result) {
        boolean same = switch (result) {
            case PayoutTransferResult.Succeeded succeeded -> payout.getStatus() == ExchangePayoutStatus.COMPLETED
                    && succeeded.transactionId() != null
                    && succeeded.transactionId().equals(payout.getPayoutTransactionId());
            case PayoutTransferResult.Failed ignored -> payout.getStatus() == ExchangePayoutStatus.FAILED;
            default -> true;
        };
        if (!same && payout.getStatus() != ExchangePayoutStatus.MANUAL_REVIEW_REQUIRED) {
            log.error("이미 끝난 환전 송금 시도와 다른 결과를 받았다(반영하지 않음): exchangeId={}, attemptNo={}, status={}, result={}",
                    exchange.getId(), payout.getAttemptNo(), payout.getStatus(), result.getClass().getSimpleName());
        }
    }

    private ExchangePayout latestOf(Long exchangeId) {
        return exchangePayoutRepository.findTopByExchangeIdOrderByAttemptNoDesc(exchangeId).orElse(null);
    }

    private ExchangeAccount accountOf(Exchange exchange) {
        return exchangeAccountRepository.findByExchangeId(exchange.getId())
                .orElseThrow(() -> inconsistent(exchange, "계좌 스냅샷 없음"));
    }

    private static PayoutTransferRequest request(Exchange exchange, ExchangePayout payout, ExchangeAccount account,
            String accountNumber) {
        return new PayoutTransferRequest(payout.getPayoutRequestId(), exchange.getId(), payout.getAmountKrw(),
                account.getBankCode(), accountNumber, account.getAccountHolderName());
    }

    private static BillingException inconsistent(Exchange exchange, String detail) {
        log.error("환전 송금 상태 불일치로 처리를 중단한다: exchangeId={}, status={}, {}", exchange.getId(),
                exchange.getStatus(), detail);
        return new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
    }

    private record Locked(Exchange exchange, ExchangePayout payout) {
    }

    // 송금 실행의 다음 단계
    public sealed interface Step {

        // 새 시도를 저장·커밋했다. 트랜잭션 밖에서 이 요청을 보낸다
        record Send(PayoutTransferRequest request) implements Step {
        }

        // 결과 대기 시도를 같은 지급 요청 ID로 확인한다. 은행에 기록이 없으면 resend(같은 ID·같은 내용)를 보낸다(null이면 보내지 않는다)
        record Check(String payoutRequestId, PayoutTransferRequest resend) implements Step {
        }

        // 은행을 부르지 않는다
        record Stop() implements Step {
        }
    }
}
