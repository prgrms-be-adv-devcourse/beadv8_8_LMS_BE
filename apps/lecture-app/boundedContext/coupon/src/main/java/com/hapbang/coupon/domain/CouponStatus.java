package com.hapbang.coupon.domain;

/**
 * 쿠폰 정책 상태. 판매자 쿠폰은 DRAFT → REVIEW → OPEN/REJECTED, 플랫폼 쿠폰은 DRAFT → OPEN이다.
 * 발급 운영은 OPEN → SUSPENDED → OPEN(재개), OPEN·SUSPENDED → CLOSED다.
 */
public enum CouponStatus {
    DRAFT,
    REVIEW,
    REJECTED,
    OPEN,
    SUSPENDED,
    CLOSED
}
