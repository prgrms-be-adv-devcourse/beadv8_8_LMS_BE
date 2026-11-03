package com.hapbang.billing.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 회원 × API별 요청 한도(#627, BD-140)의 세는 규칙. 기본 주기(1분)는 기다릴 수 없어 주기를 짧게 둔 경우를 따로 본다.
class UserRateLimiterTest {

    private static final RateLimitProperties LIMITS = new RateLimitProperties(2, 3, 1);

    private final UserRateLimiter limiter = new UserRateLimiter(LIMITS);

    @Test
    @DisplayName("기본 한도는 신청·취소 10, 일반 조회 60, 수익 조회 20이고, API마다 한도 종류가 정해져 있다")
    void defaultsAndGroups() {
        RateLimitProperties defaults = new RateLimitProperties(10, 60, 20);
        assertThat(defaults.limitOf(RateLimitedApi.Group.WRITE)).isEqualTo(10);
        assertThat(defaults.limitOf(RateLimitedApi.Group.READ)).isEqualTo(60);
        assertThat(defaults.limitOf(RateLimitedApi.Group.EARNING)).isEqualTo(20);
        assertThat(Arrays.stream(RateLimitedApi.values()).filter(api -> api.group() == RateLimitedApi.Group.WRITE))
                .hasSize(6);
        assertThat(Arrays.stream(RateLimitedApi.values()).filter(api -> api.group() == RateLimitedApi.Group.READ))
                .hasSize(6);
        assertThat(Arrays.stream(RateLimitedApi.values()).map(RateLimitedApi::tag).distinct())
                .hasSize(RateLimitedApi.values().length);
        assertThatThrownBy(() -> new RateLimitProperties(0, 60, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한도까지는 통과하고, 넘으면 다시 시도할 수 있을 때까지 남은 초(1~60)를 담아 거절한다")
    void rejectsOverLimit() {
        limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER);
        limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER);

        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    @DisplayName("API마다, 회원마다 따로 센다")
    void separateCounters() {
        limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER);
        limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER);

        assertThatCode(() -> limiter.acquire(1L, RateLimitedApi.CHARGE_CONFIRM)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(2L, RateLimitedApi.CHARGE_ORDER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.CHARGE_ORDER))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("한도 종류마다 설정한 값을 쓴다(일반 조회 3, 수익 조회 1)")
    void limitPerGroup() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1L, RateLimitedApi.WALLET);
        }
        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.WALLET))
                .isInstanceOf(RateLimitExceededException.class);

        limiter.acquire(1L, RateLimitedApi.EARNING_SUMMARY);
        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.EARNING_SUMMARY))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("주기가 지나면 다시 통과한다")
    void refillsAfterPeriod() throws InterruptedException {
        UserRateLimiter shortPeriod = new UserRateLimiter(LIMITS, Duration.ofMillis(300), System::nanoTime);
        shortPeriod.acquire(1L, RateLimitedApi.EARNING_LIST);
        assertThatThrownBy(() -> shortPeriod.acquire(1L, RateLimitedApi.EARNING_LIST))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(400);

        assertThatCode(() -> shortPeriod.acquire(1L, RateLimitedApi.EARNING_LIST)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("두 주기 넘게 쓰지 않은 회원의 카운터는 다음 주기에 메모리에서 지운다")
    void sweepsIdleCounters() {
        AtomicLong now = new AtomicLong();
        long minute = Duration.ofMinutes(1).toNanos();
        UserRateLimiter swept = new UserRateLimiter(LIMITS, Duration.ofMinutes(1), now::get);
        swept.acquire(1L, RateLimitedApi.WALLET);
        swept.acquire(2L, RateLimitedApi.WALLET);
        assertThat(swept.size()).isEqualTo(2);

        now.set(minute * 2);
        swept.acquire(2L, RateLimitedApi.WALLET);
        assertThat(swept.size()).as("2주기 전 사용은 아직 지우지 않는다(경계)").isEqualTo(2);

        now.set(minute * 3);
        swept.acquire(3L, RateLimitedApi.WALLET);
        assertThat(swept.size()).as("회원 1은 3분 동안 안 써서 지워진다").isEqualTo(2);
    }

    @Test
    @DisplayName("쉬던 카운터를 쓰는 순간 다른 요청이 정리를 돌려도, 방금 쓴 카운터는 지우지 않는다(#627 리뷰: sweep·acquire 경합)")
    void sweepDoesNotRemoveCounterInUse() throws Exception {
        AtomicLong now = new AtomicLong();
        long round = Duration.ofMinutes(3).toNanos();
        UserRateLimiter raced = new UserRateLimiter(new RateLimitProperties(1_000_000, 1_000_000, 1_000_000),
                Duration.ofMinutes(1), now::get);
        raced.acquire(1L, RateLimitedApi.WALLET);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            for (int i = 1; i <= 2_000; i++) {
                // 회원 1의 카운터는 지난 회차(3분 전)에 쓰여 이제 쉬는 상태다. 회원 1이 쓰는 순간 회원 2의 요청이 정리를 돌린다
                now.set(round * i);
                Future<?> user = executor.submit(() -> {
                    start.await();
                    raced.acquire(1L, RateLimitedApi.WALLET);
                    return null;
                });
                Future<?> sweeper = executor.submit(() -> {
                    start.await();
                    raced.acquire(2L, RateLimitedApi.WALLET);
                    return null;
                });
                user.get(5, TimeUnit.SECONDS);
                sweeper.get(5, TimeUnit.SECONDS);

                assertThat(raced.contains(1L, RateLimitedApi.WALLET)).as("%d회차", i).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("거절 횟수를 API별 지표로 낸다. 거절이 없어도 API마다 0으로 나온다")
    void rejectedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        limiter.bindTo(registry);
        limiter.acquire(1L, RateLimitedApi.EARNING_LIST);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.EARNING_LIST))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        assertThat(rejected(registry, "earning-list")).isEqualTo(2);
        assertThat(rejected(registry, "charge-order")).isZero();
        assertThat(registry.find(UserRateLimiter.REJECTED_METRIC).functionCounters())
                .hasSize(RateLimitedApi.values().length);
    }

    private static double rejected(SimpleMeterRegistry registry, String api) {
        return registry.get(UserRateLimiter.REJECTED_METRIC).tag("api", api).functionCounter().count();
    }
}
