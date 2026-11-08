package com.hapbang.coupon.out;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.MemberCoupon;

/** 회원이 가진 발급 쿠폰과 그 쿠폰 정책(할인·대상 표시용) */
public record OwnedCoupon(MemberCoupon memberCoupon, Coupon coupon) {
}
