package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 플랫폼 쿠폰 재원 설정 ({@code hapbang.coupon.platform-funding-enabled}). 값을 주지 않으면 코드 기본값이다.
 * <p>
 * <b>확정 전 값이다.</b> 플랫폼 부담분을 보류·지급·반환할 지갑 계약이 아직 없어 플랫폼 부담이 있는 주문은 강의가 막는다. 그동안 기본값
 * {@code false}로 플랫폼 쿠폰을 사용 경로에서 뺀다: 계산·미리보기의 추천 후보에서 빼고, 직접 고르면 409 {@code COUPON_NOT_USABLE}
 * (detail {@code PLATFORM_FUNDING_DISABLED})이며, 강의에 쓸 수 있는 쿠폰 목록에 넣지 않는다. 쿠폰함(내 쿠폰 목록)에는 그대로 보인다.
 * 지갑 계약이 붙으면 {@code true}로 바꾼다.
 *
 * @param platformFundingEnabled 플랫폼 쿠폰을 사용 경로에 내보내는가. 기본 false
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponPlatformFundingProperties(Boolean platformFundingEnabled) {

    public CouponPlatformFundingProperties {
        platformFundingEnabled = platformFundingEnabled != null && platformFundingEnabled;
    }

    /** 모두 기본값(플랫폼 재원 비활성) */
    public static CouponPlatformFundingProperties defaults() {
        return new CouponPlatformFundingProperties(null);
    }
}
