package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원이 가진 발급 쿠폰 한 장과 그 정책의 할인 조건.
 *
 * @param couponType 쿠폰 종류(SELLER 판매자 쿠폰·PLATFORM 플랫폼 쿠폰). 결제 화면에서 판매자 칸·플랫폼 칸을 나누는 데 쓴다
 */
public record MemberCouponView(
        Long memberCouponId,
        Long couponId,
        String couponName,
        @Schema(description = "쿠폰 종류. SELLER(판매자 쿠폰)·PLATFORM(플랫폼 쿠폰). 결제 화면에서 판매자 칸·플랫폼 칸을 나누는 데 쓴다")
        CouponType couponType,
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
        return new MemberCouponView(memberCoupon.getId(), memberCoupon.getCouponId(), coupon.getName(), coupon.getType(),
                memberCoupon.getStatus(), memberCoupon.getIssuedAt(), memberCoupon.getExpiresAt(), coupon.getTargetType(),
                coupon.getLectureId(), coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getMaxDiscountAmount(),
                coupon.isStackable());
    }
}
