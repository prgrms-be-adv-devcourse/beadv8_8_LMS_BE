package com.hapbang.coupon.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 쿠폰의 현재 시각. 쿠폰 시각은 {@code Instant}(UTC)라 시간대가 없다. 주입할 때는 {@code @Qualifier(CouponClockConfig.CLOCK)}를 붙인다.
 */
@Configuration(proxyBeanMethods = false)
public class CouponClockConfig {

    /** 같은 JVM의 강의 시계(lectureClock)와 겹치지 않도록 이름을 구분한다 */
    public static final String CLOCK = "couponClock";

    /** 시계의 최소 단위. PostgreSQL {@code timestamptz}의 정밀도(마이크로초)와 같다 */
    private static final Duration TICK = Duration.ofNanos(1_000);

    // 이름으로 주입할 때만 쓰인다. 타입만으로 Clock 하나를 찾는 다른 빈이 강의 시계와 이 시계 사이에서 고르지 못하는 것을 막는다.
    // 마이크로초로 자른다. 자르지 않으면 저장 전 엔티티의 시각과 DB에서 다시 읽은 시각이 달라진다
    @Bean(name = CLOCK, defaultCandidate = false)
    Clock couponClock() {
        return Clock.tick(Clock.systemUTC(), TICK);
    }
}
