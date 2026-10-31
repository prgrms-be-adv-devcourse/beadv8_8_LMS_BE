package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// Billing 내부 보류 반환 요청 (#241). 환불 PG 취소가 실패로 확정된 금액·환전 최종 거절·취소 금액을 보류한 회원에게 되돌린다.
// 반환 규칙은 강의·출연 반환(HoldFacade.release)과 같다(충전 HP 우선·충전 원금 배분 역순 복원). chargeId를 주면 그 충전 건 배분에서만 반환한다
// (CHARGE_ONLY 보류에서 실패가 확정된 PG 결제 건만 되돌리고, 결과를 모르는 건은 보류로 남길 때). 충전 건을 지정하면 금액은 필수다.
public record InternalReleaseCommand(Long userId, BillingReferenceType referenceType, Long referenceId,
        String releaseRequestId, Long amountHp, Long chargeId) {

    public InternalReleaseCommand {
        InternalHoldRequests.requireReference(userId, referenceType, referenceId);
        InternalHoldRequests.requireRequestId(releaseRequestId, "반환 요청 ID");
        InternalHoldRequests.requireAmountForCharge(chargeId, amountHp);
    }

    // 남은 보류 전부를 반환한다.
    public static InternalReleaseCommand full(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId) {
        return new InternalReleaseCommand(userId, referenceType, referenceId, releaseRequestId, null, null);
    }

    // 남은 보류 중 일부를 반환한다.
    public static InternalReleaseCommand partial(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId, long amountHp) {
        return new InternalReleaseCommand(userId, referenceType, referenceId, releaseRequestId, amountHp, null);
    }

    // 충전 건 하나(PG 결제 건)의 배분에서 실패가 확정된 금액만큼 반환한다. 금액은 필수다.
    public static InternalReleaseCommand ofCharge(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId, Long chargeId, long amountHp) {
        return new InternalReleaseCommand(userId, referenceType, referenceId, releaseRequestId, amountHp,
                Objects.requireNonNull(chargeId, "충전 건 ID는 필수입니다."));
    }

    public boolean isFull() {
        return amountHp == null;
    }
}
