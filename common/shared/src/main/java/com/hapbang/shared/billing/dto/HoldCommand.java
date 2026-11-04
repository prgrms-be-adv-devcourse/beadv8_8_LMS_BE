package com.hapbang.shared.billing.dto;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 결제하는 회원의 HP를 참조 대상(강의 주문 항목·출연 요청·출연 모집) 하나에 보류하는 요청이다 (내부 API holds).
// 보류 멱등키는 호출한 쪽이 정하거나 넘기지 않는다. Billing이 참조 대상으로 HOLD:{referenceType}:{referenceId}를 만든다(참조 대상당 보류 1건).
// 이 record는 필수 값·참조 유형 같은 요청 구조만 검사한다. 금액(0 이하 거절)·잔액·멱등성 같은 금융 검증은 Billing이 BillingException으로 한다.
public record HoldCommand(
        Long userId,
        BillingReferenceType referenceType,
        Long referenceId,
        long amountHp
) {

    // 보류를 만들 수 있는 참조 유형: 강의는 주문 항목(강의 하나) 단위, 출연 요청은 요청 단위, 출연 모집은 모집글 단위로 보류한다.
    // 강의는 주문 전체에 보류 하나를 만들지 않는다(2026-10-13 강의 담당 합의). 여러 강의를 함께 사면 항목마다 보류한다.
    public static final Set<BillingReferenceType> HOLDABLE_REFERENCE_TYPES = Set.copyOf(EnumSet.of(
            BillingReferenceType.LECTURE_ORDER_ITEM,
            BillingReferenceType.APPEARANCE_REQUEST,
            BillingReferenceType.APPEARANCE_RECRUIT));

    // 필수 값과 보류 가능한 참조 유형을 확인한다.
    public HoldCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        BillingRequests.requireHoldable(referenceType, referenceId);
    }
}
