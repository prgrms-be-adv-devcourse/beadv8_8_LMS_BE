package com.hapbang.billing.app;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;

// 결제하는 회원의 HP를 참조 대상(강의 주문 항목·출연 요청·출연 모집) 하나에 보류하는 요청이다.
// 보류 멱등키는 호출한 쪽이 정하지 않고 참조 대상으로 Billing이 만든다(참조 대상당 보류 1건).
public record HoldCommand(
        Long userId,
        BillingReferenceType referenceType,
        Long referenceId,
        long amountHp
) {

    // 보류를 만들 수 있는 참조 유형: 강의는 주문 항목(강의 하나) 단위, 출연 요청은 요청 단위, 출연 모집은 모집글 단위로 보류한다.
    // 강의는 주문 전체에 보류 하나를 만들지 않는다(2026-10-13 강의 담당 합의). 여러 강의를 함께 사면 항목마다 보류한다.
    static final Set<BillingReferenceType> HOLDABLE_REFERENCE_TYPES = EnumSet.of(
            BillingReferenceType.LECTURE_ORDER_ITEM,
            BillingReferenceType.APPEARANCE_REQUEST,
            BillingReferenceType.APPEARANCE_RECRUIT);

    // 보류 멱등키와 원장 작업 키의 접두사
    static final String KEY_PREFIX = "HOLD";

    // 필수 값과 보류 가능한 참조 유형을 확인하고, 0 이하 금액을 INVALID_POINT_AMOUNT로 거절한다.
    public HoldCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        Objects.requireNonNull(referenceType, "참조 유형은 필수입니다.");
        Objects.requireNonNull(referenceId, "참조 ID는 필수입니다.");
        if (!HOLDABLE_REFERENCE_TYPES.contains(referenceType)) {
            throw new IllegalArgumentException("보류할 수 없는 참조 유형입니다: " + referenceType);
        }
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }

    // 참조 대상으로 결정론적인 보류 멱등키 HOLD:{referenceType}:{referenceId}를 만든다. 원장 작업 키로도 같은 값을 쓴다.
    // 참조 유형은 Enum 이름으로만 표기하므로 같은 대상이 다른 문자열로 표기되어 보류가 중복 생성되지 않는다.
    public String idempotencyKey() {
        return holdKey(referenceType, referenceId);
    }

    // 참조 대상의 보류 멱등키를 만든다. 반환처럼 참조 대상으로 기존 보류를 찾을 때도 같은 규칙을 쓴다.
    static String holdKey(BillingReferenceType referenceType, Long referenceId) {
        return KEY_PREFIX + ":" + referenceType.name() + ":" + referenceId;
    }
}
