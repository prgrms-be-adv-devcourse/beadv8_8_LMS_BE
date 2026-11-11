package com.hapbang.billing.out;

// 예산 예약 하나에 붙은 한 상태의 프로모션 보류 금액 합이다.
public record PromotionHoldSum(Long budgetReservationId, Long amountHp) {
}
