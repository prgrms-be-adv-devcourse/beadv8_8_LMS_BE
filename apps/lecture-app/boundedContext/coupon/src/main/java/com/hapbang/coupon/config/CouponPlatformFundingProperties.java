package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플랫폼 쿠폰 사용 재원 설정. 기본값은 true이며, false를 명시하면 계산·추천·사용 가능 목록에서 플랫폼 쿠폰을 제외한다.
 * 예산 예약과 주문 항목의 구매자·플랫폼 재원 처리는 billing 내부 API로 수행한다.
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponPlatformFundingProperties(Boolean platformFundingEnabled) {

    public CouponPlatformFundingProperties {
        platformFundingEnabled = platformFundingEnabled == null || platformFundingEnabled;
    }

    /** 모두 기본값(플랫폼 재원 활성) */
    public static CouponPlatformFundingProperties defaults() {
        return new CouponPlatformFundingProperties(null);
    }
}
