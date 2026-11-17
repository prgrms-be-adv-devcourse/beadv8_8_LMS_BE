package com.hapbang.coupon.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 모듈 테스트 컨텍스트의 지표 레지스트리. 운영(lecture-app)은 actuator가 MeterRegistry 빈을 주지만, 쿠폰 모듈 테스트 클래스패스에는
 * actuator가 없다. 테스트 컨텍스트의 컴포넌트 스캔({@code com.hapbang})이 이 설정을 읽는다
 */
@Configuration(proxyBeanMethods = false)
public class CouponTestMeterRegistryConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
