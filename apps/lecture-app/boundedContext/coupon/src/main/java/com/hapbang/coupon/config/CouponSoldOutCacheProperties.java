package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 매진 메모리 캐시의 크기 설정 ({@code hapbang.coupon.*}). 재사용 시간({@code sold-out-cache-ttl})은
 * {@link CouponIssueDeliveryProperties}에 있다. 값을 주지 않으면 코드 기본값이며 측정·합의 전 값이다.
 *
 * @param soldOutCacheMaxEntries 인스턴스마다 메모리에 담는 쿠폰 수의 상한. 없는 쿠폰 ID로 반복 요청해도 이만큼만 쌓인다. 기본 10 000
 */
@ConfigurationProperties("hapbang.coupon")
public record CouponSoldOutCacheProperties(Integer soldOutCacheMaxEntries) {

    public CouponSoldOutCacheProperties {
        soldOutCacheMaxEntries = soldOutCacheMaxEntries == null ? 10_000 : soldOutCacheMaxEntries;
        if (soldOutCacheMaxEntries < 1) {
            throw new IllegalArgumentException("hapbang.coupon.sold-out-cache-max-entries는 1 이상이어야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponSoldOutCacheProperties defaults() {
        return new CouponSoldOutCacheProperties(null);
    }
}
