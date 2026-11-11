package com.hapbang.shared.billing.dto;

// 쿠폰 예산 예약 결과. reservationId는 지갑이 정한 예산 예약 ID("PB-{숫자}")다.
// replayed가 true면 같은 workId로 다시 보내 처음 결과를 돌려받은 것이다(금액은 처음 예약 그대로).
public record PromotionBudgetReserveResultDto(
        String workId,
        String reservationId,
        long couponId,
        long amountHp,
        boolean replayed
) {
}
