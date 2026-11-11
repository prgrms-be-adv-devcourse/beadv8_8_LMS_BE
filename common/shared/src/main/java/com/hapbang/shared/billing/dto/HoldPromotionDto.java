package com.hapbang.shared.billing.dto;

// 주문 항목 보류의 플랫폼 몫 하나(플랫폼 쿠폰 하나). 지갑이 couponId로 그 쿠폰의 예산 예약을 찾아 남은 예산에서 보류한다.
// memberCouponId·generation은 지갑이 기록·대조만 한다. 이 record는 쿠폰·발급 쿠폰 ID와 세대 같은 요청 구조만 검사하고
// 금액은 검사하지 않는다(0 이하는 지갑이 INVALID_POINT_AMOUNT로 거절한다).
public record HoldPromotionDto(
        long couponId,
        long memberCouponId,
        long generation,
        long amountHp
) {

    public HoldPromotionDto {
        BillingRequests.requirePositiveId(couponId, "쿠폰 ID");
        BillingRequests.requirePositiveId(memberCouponId, "발급 쿠폰 ID");
        if (generation < 0) {
            throw new IllegalArgumentException("사용 세대는 0 이상이어야 합니다: " + generation);
        }
    }
}
