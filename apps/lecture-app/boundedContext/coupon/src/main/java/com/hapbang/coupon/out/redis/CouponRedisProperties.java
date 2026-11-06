package com.hapbang.coupon.out.redis;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠폰 Redis 설정 ({@code hapbang.coupon.redis.*}). 값을 주지 않으면 기본값이다. 측정 전 값이라 확정이 아니다.
 *
 * @param keysExpireMargin 쿠폰별 key를 발급 종료 뒤 얼마나 더 남길지. 기본 7일
 */
@ConfigurationProperties("hapbang.coupon.redis")
public record CouponRedisProperties(Duration keysExpireMargin) {

    public CouponRedisProperties {
        keysExpireMargin = keysExpireMargin == null ? Duration.ofDays(7) : keysExpireMargin;
        if (keysExpireMargin.isNegative()) {
            throw new IllegalArgumentException("hapbang.coupon.redis.keys-expire-margin은 음수일 수 없다");
        }
    }

    /** 쿠폰별 key의 만료 시각(발급 종료 + 여유) */
    public Instant keysExpireAt(Instant issueEndAt) {
        return issueEndAt.plus(keysExpireMargin);
    }
}
