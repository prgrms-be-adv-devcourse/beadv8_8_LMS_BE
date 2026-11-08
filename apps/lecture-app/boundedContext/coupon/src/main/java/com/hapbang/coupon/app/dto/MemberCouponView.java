package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

/** 회원이 가진 발급 쿠폰 한 장과 그 정책의 할인 조건 */
public record MemberCouponView(
        Long memberCouponId,
        Long couponId,
        String couponName,
        MemberCouponStatus status,
        Instant issuedAt,
        Instant expiresAt,
        CouponTargetType targetType,
        Long lectureId,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        boolean stackable
) {

    /** @param coupon 발급 쿠폰의 쿠폰 정책 */
    public static MemberCouponView of(MemberCoupon memberCoupon, Coupon coupon) {
        return new MemberCouponView(memberCoupon.getId(), memberCoupon.getCouponId(), coupon.getName(), memberCoupon.getStatus(),
                memberCoupon.getIssuedAt(), memberCoupon.getExpiresAt(), coupon.getTargetType(), coupon.getLectureId(),
                coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getMaxDiscountAmount(), coupon.isStackable());
    }
}
