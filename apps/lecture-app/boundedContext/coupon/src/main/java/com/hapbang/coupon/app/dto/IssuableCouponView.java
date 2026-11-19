package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;

/**
 * 쿠폰 받기 화면 목록의 쿠폰 한 건. {@link CouponSummaryView}와 같은 필드에 대상 강의 제목을 더했다(받을 수 있는 쿠폰 목록
 * {@code GET /api/v1/coupons}의 응답은 그대로 두려고 따로 둔다).
 *
 * @param lectureTitle 특정 강의 대상(LECTURE)이면 강의 앱에서 읽은 지금 강의 제목, 모든 강의 대상이면 {@code null}
 * @param soldOut      매진 표시(Redis 남은 재고 0 이하 또는 DB 발급 수가 수량에 닿음. Redis 장애면 DB 발급 수만 본다)
 * @param issued       조회한 회원이 이미 받았는가(발급 쿠폰이 있거나 접수됨)
 */
public record IssuableCouponView(
        Long couponId,
        String name,
        CouponType type,
        CouponTargetType targetType,
        Long lectureId,
        String lectureTitle,
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

    public static IssuableCouponView of(Coupon coupon, String lectureTitle, boolean soldOut, boolean issued) {
        return new IssuableCouponView(coupon.getId(), coupon.getName(), coupon.getType(), coupon.getTargetType(),
                coupon.getLectureId(), lectureTitle, coupon.getDiscountType(), coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(), coupon.isStackable(), coupon.getIssueStartAt(), coupon.getIssueEndAt(),
                coupon.getExpiryType(), coupon.getExpiryDays(), coupon.getFixedExpiresAt(), coupon.getQuantity(),
                coupon.getIssuedCount(), soldOut, issued, coupon.getStatus());
    }
}
