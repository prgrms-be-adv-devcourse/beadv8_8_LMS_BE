package com.hapbang.coupon.domain.usage;

import com.hapbang.coupon.domain.CouponType;

/**
 * 항목에 적용한 쿠폰 한 장의 계산 결과. d = f + s이고, 판매자 쿠폰은 f = 0, 플랫폼 쿠폰은 s = 0이다.
 *
 * @param applyOrder         항목 안 적용 순서(1부터). 판매자 쿠폰 → 플랫폼 쿠폰
 * @param baseAmount         적용 직전 남은 금액
 * @param discount           실제 할인 d
 * @param platformAmount     플랫폼 부담 f
 * @param sellerAmount       판매자 부담 s
 * @param expectedGeneration 계산할 때의 발급 쿠폰 세대. 예약 CAS의 기대값이다
 */
public record AppliedCoupon(
        long memberCouponId,
        long couponId,
        CouponType type,
        int applyOrder,
        long baseAmount,
        long discount,
        long platformAmount,
        long sellerAmount,
        long expectedGeneration,
        int policyVersion
) {
}
