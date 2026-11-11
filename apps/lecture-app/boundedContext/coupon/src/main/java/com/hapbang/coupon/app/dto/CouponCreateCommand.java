package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponTerms;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;

/**
 * 쿠폰 발행 입력. 강의 가격(basePrice)은 받지 않는다: 특정 강의 대상이면 강의 조회 내부 API로 읽어 snapshot한다.
 */
public record CouponCreateCommand(
        CouponTargetType targetType,
        Long lectureId,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        int quantity,
        Instant issueStartAt,
        Instant issueEndAt,
        ExpiryType expiryType,
        Integer expiryDays,
        Instant fixedExpiresAt,
        boolean stackable
) {

    /** 발행 조건. 특정 강의 대상이 아니면 {@code basePrice}는 {@code null}이다 */
    public CouponTerms toTerms(Long basePrice) {
        return new CouponTerms(targetType, lectureId, basePrice, discountType, discountValue, maxDiscountAmount,
                quantity, issueStartAt, issueEndAt, expiryType, expiryDays, fixedExpiresAt, stackable);
    }
}
