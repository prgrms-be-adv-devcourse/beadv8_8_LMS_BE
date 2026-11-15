package com.hapbang.auxiliary.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

// 키별 요청 한도(지갑 #627 구조, #715). 한도·주기·키 수 상한·만료를 확인한다.
class KeyedRateLimiterTest {

    @Test
    @DisplayName("주기 안에 한도까지는 통과하고, 넘으면 다시 시도할 때까지 남은 초(1 이상, 주기 이하)를 담아 거절한다")
    void rejectsOverLimit() {
        KeyedRateLimiter limiter = new KeyedRateLimiter("test", 2, Duration.ofMinutes(1), 100);

        limiter.acquire("1");
        limiter.acquire("1");

        assertThatThrownBy(() -> limiter.acquire("1"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e ->
                        assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    @DisplayName("키마다 따로 센다")
    void countsPerKey() {
        KeyedRateLimiter limiter = new KeyedRateLimiter("test", 1, Duration.ofMinutes(1), 100);

        limiter.acquire("1");
        limiter.acquire("2");

        assertThatThrownBy(() -> limiter.acquire("1")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("주기가 지나면 다시 한도만큼 통과한다")
    void refillsAfterPeriod() throws InterruptedException {
        KeyedRateLimiter limiter = new KeyedRateLimiter("test", 1, Duration.ofMillis(200), 100);

        limiter.acquire("1");
        assertThatThrownBy(() -> limiter.acquire("1")).isInstanceOf(RateLimitExceededException.class);
        Thread.sleep(300);

        limiter.acquire("1");
    }

    @Test
    @DisplayName("키 수가 상한을 넘으면 오래된 키부터 지워 메모리가 회원 수만큼 늘지 않는다")
    void boundedKeys() {
        KeyedRateLimiter limiter = new KeyedRateLimiter("test", 1, Duration.ofMinutes(1), 10);

        for (int i = 0; i < 1_000; i++) {
            limiter.acquire(String.valueOf(i));
        }

        assertThat(limiter.size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("두 주기 동안 쓰지 않은 키는 지운다")
    void expiresIdleKeys() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        KeyedRateLimiter limiter = new KeyedRateLimiter("test", 1, Duration.ofMinutes(1), 100, ticker);

        limiter.acquire("1");
        assertThat(limiter.size()).isEqualTo(1);
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(2) + 1);

        assertThat(limiter.size()).isZero();
    }
}
