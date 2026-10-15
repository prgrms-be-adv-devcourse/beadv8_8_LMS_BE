package com.hapbang.billing.app;

import org.springframework.stereotype.Service;

import com.hapbang.billing.app.ChargeProcessing.Claim;
import com.hapbang.billing.app.ChargeProcessing.Completion;
import com.hapbang.billing.app.ChargeProcessing.RecoveryTarget;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.out.TossPayment;
import com.hapbang.billing.out.TossPaymentsClient;
import com.hapbang.billing.out.TossResult;

import lombok.RequiredArgsConstructor;

// 충전 결제를 승인하고 결과를 확정한다 (#224). 트랜잭션을 열지 않는다: DB 단계는 ChargeProcessing의 짧은 트랜잭션,
// TossPayments 호출은 그 사이(트랜잭션 밖)에서 한다. PG와 DB는 한 트랜잭션으로 묶을 수 없으므로 승인 요청 전에 PROCESSING과
// 결제키를 먼저 커밋해 두어, 이후 어느 단계가 실패해도 결제 조회로 다시 확정할 수 있게 한다.
// - 승인 성공(DONE) → 충전 확정(COMPLETED + 충전 원장)
// - 승인 실패 확정(4xx) → FAILED, HP 변화 없음
// - 결과 불명확(시간 초과·5xx·이미 처리됨) → 결제 조회. DONE이면 확정, 승인 안 됨이 확정되면 FAILED, 여전히 모르면 PROCESSING 유지
// - 승인 후 DB 반영 실패 → PROCESSING으로 남고, 같은 결제의 재요청·결과 확인 배치가 결제 조회로 다시 확정한다(중복 승인 없음)
@Service
@RequiredArgsConstructor
public class ChargeConfirmUseCase {

    private final ChargeProcessing chargeProcessing;
    private final TossPaymentsClient tossPaymentsClient;

    public ChargeResult confirm(Long userId, String paymentKey, String orderId, long amount) {
        Claim claim = chargeProcessing.claim(userId, orderId, amount, paymentKey);
        return switch (claim) {
            case Claim.Replay replay -> replay.result();
            case Claim.Start start -> resolveConfirm(new RecoveryTarget(start.chargeId(), orderId, amount, paymentKey),
                    tossPaymentsClient.confirm(paymentKey, orderId, amount));
            case Claim.Recover recover -> recover(recover.chargeId());
        };
    }

    // PROCESSING 충전 건의 결과를 결제 조회로 확인한다. 이미 끝난 건이면 현재 결과를 돌려준다.
    // 결제가 아직 승인 전(조회 404·READY·IN_PROGRESS)이면 같은 멱등키로 승인을 한 번 다시 요청한다(우리 승인 요청이 PG에 닿지
    // 않은 경우). 같은 멱등키라 처음 요청이 PG에서 처리됐다면 그 응답이, 처리 중이면 409가 오므로 중복 승인되지 않는다.
    public ChargeResult recover(Long chargeId) {
        return chargeProcessing.recoveryTarget(chargeId)
                .map(target -> lookup(target, true, null))
                .orElseGet(() -> chargeProcessing.result(chargeId));
    }

    private ChargeResult resolveConfirm(RecoveryTarget target, TossResult result) {
        return switch (result) {
            case TossResult.Paid paid -> applyPayment(target, paid.payment(), false, null);
            case TossResult.Rejected rejected -> chargeProcessing.fail(target.chargeId(),
                    rejected.code() + ": " + rejected.message());
            case TossResult.Unknown unknown -> lookup(target, false, "CONFIRM_" + unknown.reason());
            // 승인 API는 NotFound를 돌려주지 않는다(조회 전용). 모르는 결과로 본다
            case TossResult.NotFound notFound -> lookup(target, false, "CONFIRM_" + notFound.code());
        };
    }

    private ChargeResult lookup(RecoveryTarget target, boolean allowReconfirm, String previousReason) {
        TossResult result = tossPaymentsClient.findByOrderId(target.orderId());
        return switch (result) {
            case TossResult.Paid paid -> applyPayment(target, paid.payment(), allowReconfirm, previousReason);
            case TossResult.Rejected rejected -> unresolved(target, previousReason, "LOOKUP_" + rejected.code());
            case TossResult.Unknown unknown -> unresolved(target, previousReason, unknown.reason());
            // 결제 없음 = 아직 승인되지 않음. 실패로 끝내지 않고(승인 요청이 늦게 닿을 수 있다) 결과 확인에서만 재승인한다
            case TossResult.NotFound notFound -> reconfirmOrUnresolved(target, allowReconfirm, previousReason,
                    "LOOKUP_404:" + notFound.code());
        };
    }

    // PG 결제 상태로 충전을 확정·실패·보류한다.
    private ChargeResult applyPayment(RecoveryTarget target, TossPayment payment, boolean allowReconfirm,
            String previousReason) {
        String status = payment.status() == null ? "UNKNOWN" : payment.status();
        return switch (status) {
            case TossPayment.STATUS_DONE -> complete(target, payment);
            case "READY", "IN_PROGRESS" -> reconfirmOrUnresolved(target, allowReconfirm, previousReason,
                    "PG_STATUS_" + status);
            // 승인되지 않고 끝났거나, 승인 뒤 PG에서 전액 취소되었다(돈이 회원에게 돌아갔고 충전하지 않았으므로 HP는 바꾸지 않는다)
            case "ABORTED", "EXPIRED", "CANCELED" ->
                    chargeProcessing.fail(target.chargeId(), "PG_STATUS_" + status);
            // 일부 취소(PARTIAL_CANCELED: 돈 일부가 PG에 남음), 가상계좌 입금 대기 등은 실패로 끝내지 않고 운영자 확인 대상으로 남긴다
            default -> unresolved(target, previousReason, "PG_STATUS_" + status);
        };
    }

    // 승인 전인 결제를 결과 확인에서만 같은 멱등키로 다시 승인한다. 재승인 결과가 거절(세션 만료 등)이면 FAILED,
    // 승인이면 확정, 모르면 PROCESSING 유지다. 결제키 원문을 읽을 수 없으면 재승인할 수 없어 운영자 확인으로 남긴다.
    private ChargeResult reconfirmOrUnresolved(RecoveryTarget target, boolean allowReconfirm, String previousReason,
            String reason) {
        if (!allowReconfirm) {
            return unresolved(target, previousReason, reason);
        }
        if (target.paymentKey() == null) {
            return unresolved(target, "PAYMENT_KEY_UNREADABLE", reason);
        }
        return resolveConfirm(target, tossPaymentsClient.confirm(target.paymentKey(), target.orderId(),
                target.amount()));
    }

    private ChargeResult complete(RecoveryTarget target, TossPayment payment) {
        Completion completion = chargeProcessing.complete(target.chargeId(), payment);
        if (completion instanceof Completion.Completed completed) {
            return completed.result();
        }
        throw new BillingException(BillingErrorCode.CHARGE_STATE_INCONSISTENT);
    }

    private ChargeResult unresolved(RecoveryTarget target, String previousReason, String reason) {
        return chargeProcessing.noteUnresolved(target.chargeId(),
                previousReason == null ? reason : previousReason + " / " + reason);
    }
}
