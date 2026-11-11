package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 발급 쿠폰 만료 배치 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CouponExpiryProperties.class, CouponBudgetWorkProperties.class})
public class CouponExpiryConfig {
}
