package com.hapbang.billing.app;

import java.util.Objects;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 강의·출연 HP 결제 보류 요청 (#494). 내부 API가 받은 shared HoldCommand를 in.BillingCommands가 바꿔 넘긴다.
// 보류 멱등키는 Billing이 참조 대상으로 만든다(HOLD:{referenceType}:{referenceId}, 참조 대상당 보류 1건).
// 금액(0 이하 거절)은 BillingCommandValidator가 검사한다.
public record PaymentHoldCommand(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp) {

    public PaymentHoldCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
    }
}
