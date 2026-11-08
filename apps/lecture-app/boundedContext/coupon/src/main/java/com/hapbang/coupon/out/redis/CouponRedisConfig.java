package com.hapbang.coupon.out.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 쿠폰 Redis 설정값 등록. {@code StringRedisTemplate}은 common/redis 자동 설정(Boot Redis 자동 설정)이 준다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponRedisProperties.class)
public class CouponRedisConfig {
}
