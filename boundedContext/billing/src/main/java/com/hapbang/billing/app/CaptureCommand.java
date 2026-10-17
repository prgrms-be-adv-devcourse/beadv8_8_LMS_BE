package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류 HP 확정 차감 요청 (#241). 보류 HP를 다른 회원에게 옮기지 않고 최종 차감한다(PG 환불 취소 성공·환전 송금 성공 확인 후).
// - amountHp가 null이면 남은 보류 전부(full), 아니면 그 금액만(부분 확정 차감).
// - chargeId는 CHARGE_ONLY 보류(환불)에서 PG 결제 건(충전 건) 하나의 취소 성공액만 확정할 때 준다. 이때 금액은 필수이며
//   PG가 실제로 취소한 금액을 그대로 넘긴다(생략해 그 배분 전부를 차감하는 일이 없게 한다).
// - 결과의 capturedHp는 요청 금액과 같다(넘으면 CAPTURE_AMOUNT_EXCEEDS_HELD로 거절, 나눠 차감하지 않는다).
// - captureRequestId는 보류 안에서 고유한 업무 요청 ID다. 같은 ID 재요청은 처음 결과, 다른 금액·충전 건이면 IDEMPOTENCY_KEY_CONFLICT.
public record CaptureCommand(Long userId, BillingReferenceType referenceType, Long referenceId,
        String captureRequestId, Long amountHp, Long chargeId) {

    public CaptureCommand {
        InternalHoldRequests.requireReference(userId, referenceType, referenceId);
        InternalHoldRequests.requireRequestId(captureRequestId, "확정 차감 요청 ID");
        InternalHoldRequests.requireAmountForCharge(chargeId, amountHp);
    }

    // 남은 보류 전부를 확정 차감한다.
    public static CaptureCommand full(Long userId, BillingReferenceType referenceType, Long referenceId,
            String captureRequestId) {
        return new CaptureCommand(userId, referenceType, referenceId, captureRequestId, null, null);
    }

    // 남은 보류 중 일부를 확정 차감한다.
    public static CaptureCommand partial(Long userId, BillingReferenceType referenceType, Long referenceId,
            String captureRequestId, long amountHp) {
        return new CaptureCommand(userId, referenceType, referenceId, captureRequestId, amountHp, null);
    }

    // 충전 건 하나(PG 결제 건)의 배분에서 PG가 실제로 취소한 금액만큼 확정 차감한다.
    public static CaptureCommand ofCharge(Long userId, BillingReferenceType referenceType, Long referenceId,
            String captureRequestId, Long chargeId, long amountHp) {
        return new CaptureCommand(userId, referenceType, referenceId, captureRequestId, amountHp,
                Objects.requireNonNull(chargeId, "충전 건 ID는 필수입니다."));
    }

    public boolean isFull() {
        return amountHp == null;
    }
}
