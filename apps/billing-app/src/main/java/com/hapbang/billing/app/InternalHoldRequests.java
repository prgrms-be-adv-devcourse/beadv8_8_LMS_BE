package com.hapbang.billing.app;

import java.util.Objects;
import java.util.regex.Pattern;

import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// Billing 내부 보류·확정 차감·반환 요청(환불·환전, #241)의 구조 검사다. shared 요청과 같은 규칙으로, 구조 오류는 호출 코드의 오류라
// NullPointerException·IllegalArgumentException으로 알린다. 금액(0 이하)은 UseCase가 INVALID_POINT_AMOUNT로 거절한다.
// 내부 보류의 종류는 참조 유형으로 정해진다: 충전 HP 환불 요청 = CHARGE_ONLY, 수익 HP 환전 = REVENUE_ONLY.
final class InternalHoldRequests {

    // shared BillingRequests.REQUEST_ID와 같은 규칙(':'는 키 구분자라 쓸 수 없다, 원장 멱등키 길이 제한)
    static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    private InternalHoldRequests() {
    }

    // 내부 보류 참조 유형의 보류 종류. 강의·출연 참조 유형은 내부 HTTP API(MIXED)로만 보류한다.
    static PointHoldType holdTypeOf(BillingReferenceType referenceType) {
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        return switch (referenceType) {
            case POINT_REFUND_REQUEST -> PointHoldType.CHARGE_ONLY;
            case EXCHANGE -> PointHoldType.REVENUE_ONLY;
            default -> throw new IllegalArgumentException("Billing 내부 보류 참조 유형이 아닙니다: " + referenceType);
        };
    }

    static void requireReference(Long userId, BillingReferenceType referenceType, Long referenceId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        holdTypeOf(referenceType);
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
    }

    // 충전 건을 지정한 확정 차감·반환은 금액이 필수다. PG 결제 건별 결과 금액만 처리하고, 생략해 그 배분 전부를 처리하는 일을 막는다.
    static void requireAmountForCharge(Long chargeId, Long amountHp) {
        if (chargeId != null && amountHp == null) {
            throw new IllegalArgumentException("충전 건을 지정한 처리는 금액이 필수입니다: chargeId=" + chargeId);
        }
    }

    static void requireRequestId(String requestId, String name) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException(name + " 형식이 올바르지 않습니다: " + requestId);
        }
    }
}
