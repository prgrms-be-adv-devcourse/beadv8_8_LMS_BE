/**
 * 쿠폰 모듈. lecture-app pod 안에서 강의와 함께 실행되지만 JVM이 나뉘어 있다고 가정한다.
 * 강의 ↔ 쿠폰은 내부 HTTP API와 Kafka로만 통신하고, 강의 패키지를 참조하지 않는다.
 * 설계는 {@code apps/lecture-app/boundedContext/coupon/docs/}에 있다.
 */
@ApplicationModule(displayName = "Coupon", allowedDependencies = {})
package com.hapbang.coupon;

import org.springframework.modulith.ApplicationModule;
