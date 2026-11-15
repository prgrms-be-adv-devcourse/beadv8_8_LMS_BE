package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 발급 전달·매진 캐시 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CouponIssueDeliveryProperties.class, CouponSoldOutCacheProperties.class})
public class CouponIssueDeliveryConfig {
}
