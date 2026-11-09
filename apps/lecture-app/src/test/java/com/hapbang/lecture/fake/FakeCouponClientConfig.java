package com.hapbang.lecture.fake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 모듈 테스트 컨텍스트에 {@link FakeCouponClient}를 실제 쿠폰 Client보다 우선해 등록한다 */
@Configuration(proxyBeanMethods = false)
public class FakeCouponClientConfig {

    @Bean
    @Primary
    FakeCouponClient fakeCouponClient() {
        return new FakeCouponClient();
    }
}
