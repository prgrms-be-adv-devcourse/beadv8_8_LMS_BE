package com.hapbang.coupon.domain.usage;

/** 구매자가 고른 매핑 하나: 발급 쿠폰을 어느 강의(주문 항목)에 둘지 */
public record Selection(long memberCouponId, long lectureId) {
}
