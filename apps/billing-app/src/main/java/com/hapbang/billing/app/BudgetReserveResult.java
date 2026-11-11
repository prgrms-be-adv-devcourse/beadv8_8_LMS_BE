package com.hapbang.billing.app;

// 쿠폰 예산 예약 결과. reservationId는 예약 행 ID다(응답에서 "PB-{ID}"로 바꾼다).
public record BudgetReserveResult(String workId, Long reservationId, long couponId, long amountHp, boolean replayed) {
}
