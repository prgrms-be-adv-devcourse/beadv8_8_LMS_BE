package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 강의·출연 HP 결제 보류의 반환 요청 (#494). 금액이 null이면 처리 시점의 남은 보류 전부를 반환한다.
// 반환 요청 ID는 보류 하나 안에서 고유하다. 금액(0 이하 거절)은 BillingCommandValidator가 검사한다.
public record PaymentReleaseCommand(Long userId, BillingReferenceType referenceType, Long referenceId,
        String releaseRequestId, Long amountHp) {

    public PaymentReleaseCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
        PaymentHoldRequests.requireRequestId(releaseRequestId, "반환 요청 ID");
    }

    // 남은 보류 중 요청한 금액만 반환한다.
    public static PaymentReleaseCommand partial(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId, long amountHp) {
        return new PaymentReleaseCommand(userId, referenceType, referenceId, releaseRequestId, amountHp);
    }

    // 처리 시점의 남은 보류 전부를 반환한다.
    public static PaymentReleaseCommand full(Long userId, BillingReferenceType referenceType, Long referenceId,
            String releaseRequestId) {
        return new PaymentReleaseCommand(userId, referenceType, referenceId, releaseRequestId, null);
    }

    public boolean isFull() {
        return amountHp == null;
    }
}
