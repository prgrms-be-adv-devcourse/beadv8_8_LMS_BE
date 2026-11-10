package com.hapbang.billing.app;

import com.hapbang.billing.domain.BudgetReservation;

// 쿠폰 예산 예약 상태. 총액 = 남은 예산 + 보류 중 + 지급됨 + 해제됨이다.
public record BudgetReservationResult(Long reservationId, long couponId, long amountHp, long remainingHp, long heldHp,
        long paidHp, long releasedHp) {

    static BudgetReservationResult from(BudgetReservation reservation) {
        return new BudgetReservationResult(reservation.getId(), reservation.getCouponId(), reservation.getAmountHp(),
                reservation.getRemainingHp(), reservation.getHeldHp(), reservation.getPaidHp(),
                reservation.getReleasedHp());
    }
}
