package com.hapbang.coupon.out;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponUsage;

/** 회원의 쿠폰 사용 시도 한 건과 그 쿠폰 정책(이름·할인 조건 표시용) */
public record UsedCoupon(CouponUsage usage, Coupon coupon) {
}
