package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 쿠폰 계산·예약 상한 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponCalculateProperties.class)
public class CouponCalculateConfig {
}
