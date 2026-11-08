package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 쿠폰 발행 정책 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponPolicyProperties.class)
public class CouponPolicyConfig {
}
