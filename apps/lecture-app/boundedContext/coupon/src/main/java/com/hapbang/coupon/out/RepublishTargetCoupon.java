package com.hapbang.coupon.out;

import com.hapbang.coupon.domain.CouponStatus;

/** 재발행 배치가 훑을 쿠폰: ID, 상태, DB의 지금 epoch */
public record RepublishTargetCoupon(Long couponId, CouponStatus status, long epoch) {
}
