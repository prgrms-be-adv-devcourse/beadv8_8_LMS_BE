package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Redis 유실 복구 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponRecoveryProperties.class)
public class CouponRecoveryConfig {
}
