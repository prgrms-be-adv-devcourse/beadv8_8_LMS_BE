package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 발급 저장소 수량·용량 상한과 적체 차단 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponLimitsProperties.class)
public class CouponLimitsConfig {
}
