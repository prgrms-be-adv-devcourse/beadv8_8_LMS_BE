package com.hapbang.shared.coupon.api;

/**
 * 가격 인상 제한 조회 내부 API({@code GET /api/v1/internal/coupons/price-raise-blocked})의 응답.
 *
 * @param lectureId 강의 ID
 * @param blocked   가격을 올릴 수 없는지. 그 강의 대상 플랫폼 쿠폰이 발급 중(OPEN)이거나, 그 쿠폰의 예약된(RESERVED) 발급 쿠폰 또는
 *                  만료 전 사용 가능한(AVAILABLE) 발급 쿠폰이 있으면 {@code true}. 판매자 쿠폰은 보지 않는다
 */
public record CouponPriceRaiseBlockedResponse(
        long lectureId,
        boolean blocked
) {
}
