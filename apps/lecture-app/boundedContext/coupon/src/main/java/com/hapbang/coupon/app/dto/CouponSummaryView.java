package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;

/**
 * 회원에게 보이는 쿠폰 정책 한 건(받을 수 있는 쿠폰 목록·쿠폰 상세). 신청자·예산·심사 정보는 싣지 않는다.
 *
 * @param soldOut 매진 표시(Redis 남은 재고 0 이하 또는 DB 발급 수가 수량에 닿음. Redis 장애면 DB 발급 수만 본다)
 * @param issued  조회한 회원이 이미 받았는가(발급 쿠폰이 있거나 접수됨)
 */
public record CouponSummaryView(
        Long couponId,
        String name,
        CouponType type,
        CouponTargetType targetType,
        Long lectureId,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        boolean stackable,
        Instant issueStartAt,
        Instant issueEndAt,
        ExpiryType expiryType,
        Integer expiryDays,
        Instant fixedExpiresAt,
        int quantity,
        int issuedCount,
        boolean soldOut,
        boolean issued,
        CouponStatus status
) {

    public static CouponSummaryView of(Coupon coupon, boolean soldOut, boolean issued) {
        return new CouponSummaryView(coupon.getId(), coupon.getName(), coupon.getType(), coupon.getTargetType(),
                coupon.getLectureId(), coupon.getDiscountType(), coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(), coupon.isStackable(), coupon.getIssueStartAt(), coupon.getIssueEndAt(),
                coupon.getExpiryType(), coupon.getExpiryDays(), coupon.getFixedExpiresAt(), coupon.getQuantity(),
                coupon.getIssuedCount(), soldOut, issued, coupon.getStatus());
    }
}
