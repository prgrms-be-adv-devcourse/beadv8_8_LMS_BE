package com.hapbang.coupon.app.dto;

/**
 * 계산 미리보기에서 발급 쿠폰 하나를 어느 강의에 쓸지 고른 매핑.
 *
 * @param memberCouponId 발급 쿠폰 ID
 * @param lectureId      쿠폰을 쓸 강의 ID
 */
public record CouponQuoteSelection(long memberCouponId, long lectureId) {
}
