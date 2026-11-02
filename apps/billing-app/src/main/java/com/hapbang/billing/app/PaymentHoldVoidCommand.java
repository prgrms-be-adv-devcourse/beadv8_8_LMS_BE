package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 강의·출연 HP 결제 보류의 보류 무효화 요청 (#479, #494). 멱등 키는 참조 대상이다(참조 대상당 한 번).
public record PaymentHoldVoidCommand(Long userId, BillingReferenceType referenceType, Long referenceId) {

    public PaymentHoldVoidCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
    }
}
