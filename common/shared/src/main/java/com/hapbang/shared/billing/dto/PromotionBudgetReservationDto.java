package com.hapbang.shared.billing.dto;

// 쿠폰 예산 예약 상태. amountHp = remainingHp + heldHp + paidHp + releasedHp이고 각 값은 0 이상이다.
public record PromotionBudgetReservationDto(
        String reservationId,
        long couponId,
        long amountHp,
        long remainingHp,
        long heldHp,
        long paidHp,
        long releasedHp
) {
}
