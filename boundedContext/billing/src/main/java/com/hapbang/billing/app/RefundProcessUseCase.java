package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.hapbang.billing.app.RefundProcessing.CancelTarget;
import com.hapbang.billing.app.RefundProcessing.Step;
import com.hapbang.billing.domain.RefundManualReviewReason;
import com.hapbang.billing.out.TossCancel;
import com.hapbang.billing.out.TossPayment;
import com.hapbang.billing.out.TossPaymentsClient;
import com.hapbang.billing.out.TossResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 환불 요청의 충전 건별 PG 부분 취소를 FIFO 순서로 하나씩 처리한다 (#260). 트랜잭션을 열지 않는다: DB 단계는 RefundProcessing의
// 짧은 트랜잭션, TossPayments 호출은 그 사이(트랜잭션 밖)에서 한다. PG 취소 요청 전에 PROCESSING을 먼저 커밋하므로 이후 어느 단계가
// 실패해도 결제 조회(취소 이력)로 다시 확정할 수 있다.
// - 취소 성공(2xx, 취소 이력에 우리 표식 1건·DONE·거래 키·금액 일치) → PG가 취소한 금액만 그 충전 건 배분에서 확정 차감, 다음 충전 건으로.
//   우리 표식 취소가 여러 건(중복 취소)·금액 불일치·DONE 아님·거래 키 없음 → 차감하지 않고 운영자 확인
// - 요청 자체 오류 거절(INVALID_REQUEST 등) → 그 충전 건 금액 반환, 뒤 순서 충전 건은 PG에 요청하지 않고 반환, 요청 종료
// - 그 밖의 4xx 거절(취소 기간 경과·부분 취소 불가·취소 불가 결제 등, PG 환급은 안 되지만 환불 청구는 남음) → 보류 유지, 운영자 확인
// - 이미 취소됨·취소 가능 금액 부족 등(우리 취소가 먼저 처리됐을 수 있음) → 결제 조회: 우리 취소가 있으면 성공, 없으면 운영자 확인
// - 결과 불명확(시간 초과·5xx·401·409·429·일시 오류 코드) → 결제 조회: 우리 취소가 있으면 성공, 없으면 UNKNOWN(보류 유지)
// - UNKNOWN·PROCESSING으로 남은 건은 결과 확인(recover)에서 결제 조회 → 없으면 같은 Idempotency-Key로 한 번 다시 요청한다
//   (처음 요청이 PG에서 처리됐다면 그 응답이, 처리 중이면 409가 와서 중복 취소되지 않는다). 재요청이 거절되면 반환·운영자 확인 전에
//   취소 이력을 한 번 더 본다(앞선 요청이 늦게 처리된 경우 반환하지 않는다)
// - PG 성공 후 DB 반영 실패 → PROCESSING으로 남고, 결과 확인이 취소 이력으로 다시 확정 차감한다(재취소 없음)
// 우리 취소는 취소 사유의 표식 "[{멱등키}]"로 찾는다. 결제키·시크릿 키는 로그에 남기지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundProcessUseCase {

    static final String CANCEL_REASON_PREFIX = "HP 환불 ";
    // 우리 취소가 먼저 처리되어 생겼을 수 있는 거절: 결제 조회로 우리 취소를 찾고, 없으면 기록과 PG가 맞지 않으므로 운영자 확인
    static final Set<String> INCONCLUSIVE_CANCEL_CODES = Set.of("ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT",
            "NOT_CANCELABLE_AMOUNT", "NOT_MATCHES_REFUNDABLE_AMOUNT", "NOT_FOUND_PAYMENT");
    // 요청 자체가 잘못되어 PG가 처리하지 않은 거절: 취소되지 않은 것이 확정이고 결제 건 문제가 아니므로 실패(반환)로 끝낸다 (#260 2차).
    // 그 밖의 4xx(취소 기간 경과·부분 취소 불가 결제수단·취소 불가 결제·환불 거절·환불 계좌 오류·문서에 없는 코드 등)는
    // PG로 돌려줄 수 없을 뿐 회원의 환불 청구는 남으므로, 반환하지 않고 보류를 유지한 채 운영자 확인으로 둔다.
    static final Set<String> REQUEST_REJECTED_CODES = Set.of("INVALID_REQUEST", "FORBIDDEN_REQUEST",
            "INCORRECT_BASIC_AUTH_FORMAT");
    // 한 번 실행에서 처리할 수 있는 단계 수 상한(무한 반복 방지). 충전 건 하나는 많아야 몇 단계다
    static final int MAX_STEPS = 1_000;

    private final RefundProcessing refundProcessing;
    private final TossPaymentsClient tossPaymentsClient;

    // 접수한 환불 요청을 처리한다(API). 결과를 기다리는 건이 있으면 기다리지 않고 현재 결과를 돌려준다.
    public RefundResult process(Long refundRequestId) {
        return run(refundRequestId, null);
    }

    // 결과 확인: 마지막 변경이 staleBefore 이전인 결과 대기 건을 조회·재요청으로 확정하고 이어서 처리한다.
    public RefundResult recover(Long refundRequestId, Instant staleBefore) {
        return run(refundRequestId, staleBefore);
    }

    private RefundResult run(Long refundRequestId, Instant staleBefore) {
        try {
            Instant resolveBefore = staleBefore;
            for (int step = 0; step < MAX_STEPS; step++) {
                Step next = refundProcessing.next(refundRequestId, resolveBefore);
                if (next instanceof Step.Stop stop) {
                    return stop.result();
                }
                // UNKNOWN 자동 복구 한도 도달: PG를 부르지 않고 운영자 확인으로 바꾼다(보류 유지)
                if (next instanceof Step.Review review) {
                    refundProcessing.requireManualReview(review.refundId(), review.reason(), null);
                    continue;
                }
                Step.Call call = (Step.Call) next;
                // 결과 확인은 한 번 실행에 한 번만 한다(여전히 모르면 다음 실행까지 기다린다. PG를 연달아 부르지 않는다)
                if (call.kind() == RefundProcessing.CallKind.RESOLVE) {
                    resolveBefore = null;
                }
                execute(call);
            }
            log.error("환불 처리 단계가 상한을 넘었다. 다음 결과 확인에서 이어서 처리한다: refundRequestId={}", refundRequestId);
        } catch (RuntimeException exception) {
            // PG 성공 후 DB 반영 실패 등. 상태는 PROCESSING·UNKNOWN으로 남아 결과 확인이 다시 확정한다
            log.error("환불 처리 중 오류, 결과 확인에서 다시 처리한다: refundRequestId={}", refundRequestId, exception);
            // 상태를 바꾸지 못하고 실패한 요청도 결과 확인 배치의 오래된 순 목록 뒤로 보낸다(같은 요청만 반복해 뒤 요청이 굶지 않게)
            try {
                refundProcessing.touchRequest(refundRequestId);
            } catch (RuntimeException touchFailure) {
                log.warn("환불 요청 확인 시각 갱신 실패: refundRequestId={}", refundRequestId, touchFailure);
            }
        }
        return refundProcessing.result(refundRequestId);
    }

    private void execute(Step.Call call) {
        CancelTarget target = call.target();
        switch (call.kind()) {
            case START -> {
                if (target.paymentKey() == null) {
                    // PG에 요청하지 않았으므로 취소되지 않은 것이 확정이다
                    refundProcessing.fail(target.refundId(), "PAYMENT_KEY_UNREADABLE");
                    return;
                }
                resolveCancel(target, cancel(target), false);
            }
            case RESOLVE -> {
                if (target.paymentKey() == null) {
                    refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.PAYMENT_KEY_UNREADABLE,
                            "PAYMENT_KEY_UNREADABLE");
                    return;
                }
                lookup(target, true, null, null, true);
            }
        }
    }

    private TossResult cancel(CancelTarget target) {
        return tossPaymentsClient.cancel(target.paymentKey(), target.idempotencyKey(), target.amountHp(),
                CANCEL_REASON_PREFIX + marker(target));
    }

    // PG 취소 응답으로 결과를 정한다. retried는 결과 확인 중 같은 멱등키로 다시 보낸 요청의 응답인지다.
    private void resolveCancel(CancelTarget target, TossResult result, boolean retried) {
        switch (result) {
            case TossResult.Paid paid -> {
                List<TossCancel> ours = ourCancels(paid.payment(), target);
                if (!ours.isEmpty()) {
                    apply(target, ours);
                } else {
                    lookup(target, false, "CANCEL_RESPONSE_WITHOUT_MARKER", null, retried);
                }
            }
            case TossResult.Rejected rejected -> {
                String reason = "CANCEL_" + rejected.httpStatus() + ":" + rejected.code();
                // 재요청의 거절은 앞선 요청이 늦게 처리됐을 수 있으므로 반환·운영자 확인 전에 취소 이력을 다시 본다
                if (INCONCLUSIVE_CANCEL_CODES.contains(rejected.code()) || retried) {
                    lookup(target, false, reason, rejected, retried);
                } else {
                    reject(target, rejected, reason);
                }
            }
            case TossResult.Unknown unknown -> lookup(target, false, "CANCEL_" + unknown.reason(), null, retried);
            // 취소 API는 NotFound를 돌려주지 않는다(조회 전용). 모르는 결과로 본다
            case TossResult.NotFound notFound -> lookup(target, false, "CANCEL_" + notFound.code(), null, retried);
        }
    }

    // 결제 조회(취소 이력)로 우리 취소를 찾는다.
    // - 있으면 그 결과로 확정한다.
    // - 없을 때: 취소 거절이 우리 취소가 먼저 처리됐을 수 있는 코드였으면 운영자 확인, 결과 확인(allowRetry)이면 같은 멱등키로
    //   한 번 다시 요청(멱등키 안전 기간이 지났으면 POST하지 않고 운영자 확인), 아니면 UNKNOWN(보류 유지, 한도 도달이면 운영자 확인).
    // - recheck(결과 확인 배치의 조회)이면 조회 전에 재확인 횟수를 늘린다(조회 실패도 센다).
    // - 조회 404(승인된 결제가 없음)는 기록과 맞지 않아 운영자 확인, 조회 실패는 UNKNOWN.
    // rejection은 조회 전에 받은 취소 거절(없으면 null)이다. 우리 취소가 없으면 그 거절의 분류대로 끝낸다.
    private void lookup(CancelTarget target, boolean allowRetry, String previousReason,
            TossResult.Rejected rejection, boolean recheck) {
        if (recheck && !refundProcessing.recordResultCheck(target.refundId())) {
            return;
        }
        TossResult result = tossPaymentsClient.findByPaymentKey(target.paymentKey(), target.idempotencyKey());
        switch (result) {
            case TossResult.Paid paid -> {
                List<TossCancel> ours = ourCancels(paid.payment(), target);
                if (!ours.isEmpty()) {
                    apply(target, ours);
                } else if (rejection != null && INCONCLUSIVE_CANCEL_CODES.contains(rejection.code())) {
                    refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.NO_MATCHING_CANCEL,
                            join(previousReason, "NO_MATCHING_CANCEL:" + paid.payment().status()));
                } else if (rejection != null) {
                    reject(target, rejection, previousReason);
                } else if (allowRetry) {
                    retry(target);
                } else {
                    refundProcessing.markUnknown(target.refundId(), join(previousReason, "NO_MATCHING_CANCEL"));
                }
            }
            case TossResult.NotFound notFound -> refundProcessing.requireManualReview(target.refundId(),
                    RefundManualReviewReason.PAYMENT_NOT_FOUND, join(previousReason, "LOOKUP_404:" + notFound.code()));
            case TossResult.Rejected rejected -> refundProcessing.markUnknown(target.refundId(),
                    join(previousReason, "LOOKUP_" + rejected.httpStatus() + ":" + rejected.code()));
            case TossResult.Unknown unknown -> refundProcessing.markUnknown(target.refundId(),
                    join(previousReason, "LOOKUP_" + unknown.reason()));
        }
    }

    // 우리 취소가 없음을 확인한 뒤 같은 멱등키로 한 번 다시 요청한다. 멱등키 안전 기간(최초 요청 + 14일)이 지났으면 POST하지 않고
    // 보류를 유지한 채 운영자 확인으로 둔다(유효기간이 지난 키는 새 취소로 처리될 수 있다).
    private void retry(CancelTarget target) {
        switch (refundProcessing.reserveRetry(target.refundId())) {
            case ALLOWED -> resolveCancel(target, cancel(target), true);
            case EXPIRED -> refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.IDEMPOTENCY_KEY_EXPIRED,
                    "NO_MATCHING_CANCEL / IDEMPOTENCY_KEY_EXPIRED: 같은 멱등키로 다시 요청할 수 있는 기간이 지났다");
            case NOT_IN_FLIGHT -> {
            }
        }
    }

    // 취소가 확정적으로 거절됐다. 요청 자체의 오류만 실패(반환)로 끝내고, 그 밖에는 환불 청구를 남겨 운영자 확인으로 둔다.
    private void reject(CancelTarget target, TossResult.Rejected rejected, String reason) {
        if (REQUEST_REJECTED_CODES.contains(rejected.code())) {
            refundProcessing.fail(target.refundId(), rejected.code() + ": " + rejected.message());
        } else {
            refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.PG_REFUND_NOT_POSSIBLE,
                    join(reason, "PG_REFUND_NOT_POSSIBLE: " + rejected.message()));
        }
    }

    // 우리 취소가 하나뿐이고 성공(DONE)했으며 거래 키가 있으면 PG가 취소한 금액으로 확정 차감한다(금액이 다르면 succeed가 운영자 확인).
    // 같은 표식의 취소가 여럿이면 중복 취소(PG에서 돈이 두 번 나감)이므로 차감하지 않고 운영자 확인으로 둔다.
    private void apply(CancelTarget target, List<TossCancel> ours) {
        if (ours.size() > 1) {
            log.error("같은 환불 건의 PG 취소가 여러 건이다. 운영자 확인 필요: refundId={}, count={}", target.refundId(),
                    ours.size());
            refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.DUPLICATE_CANCEL,
                    "DUPLICATE_CANCEL:" + ours.size());
            return;
        }
        TossCancel cancel = ours.getFirst();
        if (!cancel.isDone() || cancel.cancelAmount() == null) {
            refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.CANCEL_NOT_CONFIRMED,
                    "CANCEL_STATUS_" + cancel.cancelStatus());
            return;
        }
        if (cancel.transactionKey() == null || cancel.transactionKey().isBlank()) {
            refundProcessing.requireManualReview(target.refundId(), RefundManualReviewReason.CANCEL_NOT_CONFIRMED,
                    "CANCEL_WITHOUT_TRANSACTION_KEY");
            return;
        }
        refundProcessing.succeed(target.refundId(), cancel.transactionKey(), cancel.cancelAmount());
    }

    // 취소 이력에서 우리 표식이 있는 취소 전부
    private static List<TossCancel> ourCancels(TossPayment payment, CancelTarget target) {
        String marker = marker(target);
        return payment.cancels().stream().filter(cancel -> cancel.hasMarker(marker)).toList();
    }

    // 취소 사유에 넣어 취소 이력에서 우리 취소를 찾는 표식. 멱등키를 대괄호로 감싸 다른 요청의 키와 부분 일치하지 않게 한다
    static String marker(CancelTarget target) {
        return "[" + target.idempotencyKey() + "]";
    }

    private static String join(String previousReason, String reason) {
        return previousReason == null ? reason : previousReason + " / " + reason;
    }
}
