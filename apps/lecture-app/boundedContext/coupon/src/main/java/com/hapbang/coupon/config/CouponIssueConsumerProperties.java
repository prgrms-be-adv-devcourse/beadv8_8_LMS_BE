package com.hapbang.coupon.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 발급 확정 소비자의 재시도 설정 ({@code hapbang.coupon.issue-consumer.*}). 값을 주지 않으면 코드 기본값이다. 측정·합의 전 값이라 확정이
 * 아니다. 재시도는 같은 자리에서 기다리는 블로킹 방식이고(같은 쿠폰의 순서 유지), 간격은 {@code retryInterval}에서 시작해 두 배씩
 * {@code retryMaxInterval}까지 늘린다(팀 Kafka 규칙과 같은 모양). 재시도를 다 쓰면 DLT로 격리한다.
 *
 * @param retryAttempts    처리 실패 뒤 재시도 횟수. 기본 3
 * @param retryInterval    첫 재시도까지의 간격. 기본 1초
 * @param retryMaxInterval 재시도 간격 상한. 기본 10초
 */
@ConfigurationProperties("hapbang.coupon.issue-consumer")
public record CouponIssueConsumerProperties(Integer retryAttempts, Duration retryInterval, Duration retryMaxInterval) {

    public CouponIssueConsumerProperties {
        retryAttempts = retryAttempts == null ? 3 : retryAttempts;
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("hapbang.coupon.issue-consumer.retry-attempts는 음수일 수 없다");
        }
        retryInterval = retryInterval == null ? Duration.ofSeconds(1) : retryInterval;
        retryMaxInterval = retryMaxInterval == null ? Duration.ofSeconds(10) : retryMaxInterval;
        if (retryInterval.isNegative() || retryInterval.isZero() || retryMaxInterval.compareTo(retryInterval) < 0) {
            throw new IllegalArgumentException(
                    "hapbang.coupon.issue-consumer.retry-interval은 0보다 크고 retry-max-interval 이하여야 한다");
        }
    }

    /** 모두 기본값 */
    public static CouponIssueConsumerProperties defaults() {
        return new CouponIssueConsumerProperties(null, null, null);
    }
}
