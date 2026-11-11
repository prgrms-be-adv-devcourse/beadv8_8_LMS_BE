package com.hapbang.lecture.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 모듈 테스트 컨텍스트에 {@link FakeCouponApiClient}를 실제 쿠폰 Client보다 우선해 등록한다.
 * 실제 쿠폰 내부 API를 타는 e2e는 {@value #ENABLED}{@code =false}로 끈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = FakeCouponApiClientConfig.ENABLED, havingValue = "true", matchIfMissing = true)
public class FakeCouponApiClientConfig {

    public static final String ENABLED = "lecture.test.fake-coupon-client";

    @Bean
    @Primary
    FakeCouponApiClient fakeCouponApiClient() {
        return new FakeCouponApiClient();
    }
}
