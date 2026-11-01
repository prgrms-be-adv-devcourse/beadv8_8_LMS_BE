package com.hapbang.billing.app;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 강의·출연 HP 결제 보류 요청(Payment*Command, #494)의 구조 검사다. 구조 오류는 호출 코드의 오류라
// NullPointerException·IllegalArgumentException으로 알린다. 금액은 BillingCommandValidator가 BillingException으로 검사한다.
// 참조 유형 목록과 요청 ID 형식은 shared HTTP 계약(HoldCommand.HOLDABLE_REFERENCE_TYPES, BillingRequests.REQUEST_ID)과 같은 규칙이고,
// billing app이 shared.billing.dto에 의존하지 않도록 복사해 둔다. 일치는 PaymentHoldRequestsTest가 고정한다.
final class PaymentHoldRequests {

    // HP 결제 보류를 만들 수 있는 참조 유형: 강의 주문 항목, 출연 요청, 출연 모집
    static final Set<BillingReferenceType> HOLDABLE_REFERENCE_TYPES = Set.copyOf(EnumSet.of(
            BillingReferenceType.LECTURE_ORDER_ITEM,
            BillingReferenceType.APPEARANCE_REQUEST,
            BillingReferenceType.APPEARANCE_RECRUIT));

    private PaymentHoldRequests() {
    }

    // 참조 유형·참조 ID가 있고 HP 결제 보류를 만들 수 있는 참조 유형인지 확인한다.
    static void requireHoldable(BillingReferenceType referenceType, Long referenceId) {
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        if (!HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
    }

    // 요청 ID가 있고 형식에 맞는지 확인한다(InternalHoldRequests와 같은 형식).
    static void requireRequestId(String requestId, String name) {
        InternalHoldRequests.requireRequestId(requestId, name);
    }
}
