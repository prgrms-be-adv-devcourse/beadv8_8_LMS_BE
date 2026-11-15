package com.hapbang.coupon.out.redis;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠폰 Redis 설정 ({@code hapbang.coupon.redis.*}). 값을 주지 않으면 기본값이다. 측정 전 값이라 확정이 아니다.
 *
 * @param keysExpireMargin 쿠폰별 key를 발급 종료 뒤 얼마나 더 남길지. DB 결과가 없는 접수 기록이 남은 쿠폰은 재발행·대사 배치가 그 기록을
 *                         마지막으로 본 시각 + 이 값까지 key 만료를 늦춘다. 기본 7일
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

    /**
     * DB 결과가 없는 접수 기록이 남은 쿠폰의 key를 늦출 만료 시각(지금 + 여유). 재발행·대사 배치가 미확정 요청을 볼 때마다 이 시각까지
     * 늦추므로(줄이지는 않는다), 미확정이 남아 있는 동안은 마지막으로 본 뒤 여유만큼 key가 남는다.
     */
    public Instant extendedKeysExpireAt(Instant now) {
        return now.plus(keysExpireMargin);
    }
}
