package com.hapbang.coupon.config;

import org.springframework.context.annotation.Configuration;

/**
 * 쿠폰 모듈의 설정 진입점. lecture-app이 쿠폰 패키지를 스캔해 같은 컨텍스트에 쿠폰 빈을 올리는지 확인하는 자리이며,
 * 쿠폰 기능의 빈은 아직 없다.
 */
@Configuration(proxyBeanMethods = false)
public class CouponModuleConfig {
}
