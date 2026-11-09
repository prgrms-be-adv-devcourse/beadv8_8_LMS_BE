package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;

/** 쿠폰 정책 한 건(판매자·관리자 응답) */
public record CouponView(
        Long couponId,
        String name,
        CouponType type,
        Long applicantId,
        CouponTargetType targetType,
        Long lectureId,
        Long basePrice,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        int quantity,
        int issuedCount,
        Instant issueStartAt,
        Instant issueEndAt,
        ExpiryType expiryType,
        Integer expiryDays,
        Instant fixedExpiresAt,
        boolean stackable,
        int policyVersion,
        CouponStatus status,
        String rejectReason
) {

    public static CouponView from(Coupon coupon) {
        return new CouponView(coupon.getId(), coupon.getName(), coupon.getType(), coupon.getApplicantId(), coupon.getTargetType(),
                coupon.getLectureId(), coupon.getBasePrice(), coupon.getDiscountType(), coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(), coupon.getQuantity(), coupon.getIssuedCount(), coupon.getIssueStartAt(),
                coupon.getIssueEndAt(), coupon.getExpiryType(), coupon.getExpiryDays(), coupon.getFixedExpiresAt(),
                coupon.isStackable(), coupon.getPolicyVersion(), coupon.getStatus(), coupon.getRejectReason());
    }
}
