package com.hapbang.billing.app;

import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// Billing 내부 보류 요청 (#241). 환불은 POINT_REFUND_REQUEST(충전 HP만), 환전은 EXCHANGE(수익 HP만)로 보류한다.
// 같은 참조 대상 재요청은 처음 보류를 돌려주고, 금액·지갑이 다르면 IDEMPOTENCY_KEY_CONFLICT다(BillingPort.hold와 같다).
public record InternalHoldCommand(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp) {

    public InternalHoldCommand {
        InternalHoldRequests.requireReference(userId, referenceType, referenceId);
    }

    // 참조 유형으로 정해지는 보류 종류
    public PointHoldType holdType() {
        return InternalHoldRequests.holdTypeOf(referenceType);
    }
}
