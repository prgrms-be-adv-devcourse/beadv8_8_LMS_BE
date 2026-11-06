package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 쿠폰 발행 조건(대상·할인·수량·발급 기간·만료·중첩). 유형별 발행 검증은 {@link #validateFor(CouponType)}가 한다.
 *
 * @param lectureId         대상 강의 ID. 특정 강의 대상만
 * @param basePrice         발행 시 강의 가격 snapshot(HP). 특정 강의 대상만. 호출자가 강의 조회로 읽은 값이다
 * @param discountValue     정률이면 퍼센트 정수(1~100), 정액이면 HP
 * @param maxDiscountAmount 장당 최대 할인액(HP). 모든 강의 대상 정률 쿠폰만
 * @param expiryDays        접수 기준 만료 일수. RELATIVE만
 * @param fixedExpiresAt    고정 만료 시각. FIXED만
 */
public record CouponTerms(
        CouponTargetType targetType,
        Long lectureId,
        Long basePrice,
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

    /** 발행 검증. 어긋나면 {@link CouponDomainException}이다 */
    void validateFor(CouponType type) {
        if (type == null || targetType == null || discountType == null || expiryType == null
                || issueStartAt == null || issueEndAt == null) {
            throw new CouponDomainException(CouponDomainError.MISSING_TERMS);
        }
        if (type == CouponType.SELLER && targetType != CouponTargetType.LECTURE) {
            throw new CouponDomainException(CouponDomainError.SELLER_TARGET_LECTURE_ONLY);
        }
        if (quantity < 1) {
            throw new CouponDomainException(CouponDomainError.INVALID_QUANTITY);
        }
        if (!issueStartAt.isBefore(issueEndAt)) {
            throw new CouponDomainException(CouponDomainError.INVALID_ISSUE_PERIOD);
        }
        validateTarget();
        validateDiscount();
        validateExpiry();
    }

    private void validateTarget() {
        if (targetType == CouponTargetType.LECTURE) {
            if (lectureId == null || basePrice == null) {
                throw new CouponDomainException(CouponDomainError.LECTURE_TARGET_REQUIRED);
            }
            if (basePrice < 0) {
                throw new CouponDomainException(CouponDomainError.INVALID_BASE_PRICE);
            }
        } else if (lectureId != null || basePrice != null) {
            throw new CouponDomainException(CouponDomainError.LECTURE_TARGET_NOT_ALLOWED);
        }
    }

    private void validateDiscount() {
        if (discountType == DiscountType.RATE && (discountValue <= 0 || discountValue > 100)) {
            throw new CouponDomainException(CouponDomainError.INVALID_RATE);
        }
        if (discountType == DiscountType.AMOUNT && discountValue < 1) {
            throw new CouponDomainException(CouponDomainError.INVALID_AMOUNT);
        }
        if (requiresMaxDiscount()) {
            if (maxDiscountAmount == null || maxDiscountAmount < 1) {
                throw new CouponDomainException(CouponDomainError.MAX_DISCOUNT_REQUIRED);
            }
        } else if (maxDiscountAmount != null) {
            throw new CouponDomainException(CouponDomainError.MAX_DISCOUNT_NOT_ALLOWED);
        }
    }

    private void validateExpiry() {
        if (expiryType == ExpiryType.RELATIVE && (expiryDays == null || expiryDays < 1 || fixedExpiresAt != null)) {
            throw new CouponDomainException(CouponDomainError.INVALID_RELATIVE_EXPIRY);
        }
        if (expiryType == ExpiryType.FIXED && (fixedExpiresAt == null || expiryDays != null)) {
            throw new CouponDomainException(CouponDomainError.INVALID_FIXED_EXPIRY);
        }
    }

    /** 장당 최대 할인액을 입력하는 쿠폰인가: 모든 강의 대상 정률 쿠폰만 */
    private boolean requiresMaxDiscount() {
        return targetType == CouponTargetType.ANY_LECTURE && discountType == DiscountType.RATE;
    }

    /**
     * 프로모션 예산액(HP). 장당 최대 할인액 × 수량이다.
     * <ul>
     *     <li>특정 강의 정률: floor(강의 가격 × 할인율 / 100) × 수량</li>
     *     <li>특정 강의 정액: min(정액, 강의 가격) × 수량. 정액이 가격보다 커도 할인은 가격까지라 예산도 가격까지다</li>
     *     <li>모든 강의 정액: 정액 × 수량</li>
     *     <li>모든 강의 정률: 장당 최대 할인액 × 수량</li>
     * </ul>
     * 곱이 long을 넘으면 {@link CouponDomainError#BUDGET_OVERFLOW}다. 유형(판매자 쿠폰은 예산 없음)은 {@link Coupon#budgetAmount()}가 본다.
     */
    long budgetAmount() {
        try {
            long perCoupon = switch (targetType) {
                case LECTURE -> discountType == DiscountType.RATE
                        ? Math.multiplyExact(basePrice, discountValue) / 100
                        : Math.min(discountValue, basePrice);
                case ANY_LECTURE -> discountType == DiscountType.RATE ? maxDiscountAmount : discountValue;
            };
            return Math.multiplyExact(perCoupon, (long) quantity);
        } catch (ArithmeticException e) {
            throw new CouponDomainException(CouponDomainError.BUDGET_OVERFLOW);
        }
    }

    /**
     * 발급 쿠폰의 만료 시각. RELATIVE는 최초 접수 시각 + N일, FIXED는 고정 시각이다.
     * 입력이 최초 접수 시각이라 같은 요청의 재전송으로 바뀌지 않는다.
     */
    Instant expiresAtFor(Instant firstAcceptedAt) {
        return switch (expiryType) {
            case RELATIVE -> firstAcceptedAt.plus(Duration.ofDays(expiryDays));
            case FIXED -> fixedExpiresAt;
        };
    }
}
