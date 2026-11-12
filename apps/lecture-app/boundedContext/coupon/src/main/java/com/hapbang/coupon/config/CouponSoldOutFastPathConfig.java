package com.hapbang.coupon.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.hapbang.coupon.app.CouponSoldOutCache;
import com.hapbang.coupon.in.CouponSoldOutFastPathFilter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 매진 빠른 경로 필터 등록 */
@Configuration(proxyBeanMethods = false)
public class CouponSoldOutFastPathConfig {

    /**
     * HTTP 서버 관측 필터(Boot 기본 {@code HIGHEST_PRECEDENCE + 1}) 뒤라 매진 응답도 {@code http_server_requests}·trace에 남고,
     * Spring Security 필터 체인(Boot 기본 -100) 앞이라 인증·보안 필터를 거치지 않는다
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    FilterRegistrationBean<CouponSoldOutFastPathFilter> couponSoldOutFastPathFilter(ObjectProvider<CouponSoldOutCache> soldOutCache,
            ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<RequestMappingHandlerAdapter> handlerAdapter) {
        // 필터 등록은 내장 서버가 뜰 때라 일반 빈보다 먼저다. 캐시(Redis 연결 팩토리까지)·지표·변환기는 첫 요청 때 꺼낸다.
        // 앞당겨 만들면 테스트가 나중에 넣는 Redis 주소처럼 그 뒤에 모이는 설정을 놓친다
        var filter = new CouponSoldOutFastPathFilter(soldOutCache::getObject,
                () -> meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                () -> handlerAdapter.getObject().getMessageConverters());
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/coupons/*");
        registration.setOrder(ORDER);
        return registration;
    }
}
