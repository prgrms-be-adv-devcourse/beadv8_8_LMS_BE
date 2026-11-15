package com.hapbang.coupon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** DLT 관리자 처리 설정값 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CouponDltProperties.class)
public class CouponDltConfig {
}
