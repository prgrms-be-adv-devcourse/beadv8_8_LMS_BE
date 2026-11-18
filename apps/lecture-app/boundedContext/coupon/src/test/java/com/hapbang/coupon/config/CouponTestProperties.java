package com.hapbang.coupon.config;

/**
 * 테스트가 쓰는 쿠폰 설정 기본값. 설정 record의 compact 생성자가 null을 코드 기본값으로 바꾸므로, 운영에서 값을 주지 않고 바인딩한 것과
 * 같다.
 */
public final class CouponTestProperties {

    private CouponTestProperties() {
    }

    public static CouponCalculateProperties calculate() {
        return new CouponCalculateProperties(null, null);
    }

    public static CouponDltProperties dlt() {
        return new CouponDltProperties(null, null);
    }

    public static CouponIssueDeliveryProperties issueDelivery() {
        return new CouponIssueDeliveryProperties(null, null, null, null, null);
    }

    public static CouponLimitsProperties limits() {
        return new CouponLimitsProperties(null, null, null, null, null);
    }

    public static CouponPlatformFundingProperties platformFunding() {
        return new CouponPlatformFundingProperties(null);
    }

    public static CouponRecoveryProperties recovery() {
        return new CouponRecoveryProperties(null, null, null, null, null);
    }

    public static CouponSoldOutCacheProperties soldOutCache() {
        return new CouponSoldOutCacheProperties(null);
    }
}
