package com.hapbang.lecture.global.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 회원 × API별 요청 한도(D80)의 세는 규칙. 기본 주기(1분)는 기다릴 수 없어 주기를 짧게 둔 경우를 따로 본다.
class UserRateLimiterTest {

    private final UserRateLimiter limiter = new UserRateLimiter(new RateLimitProperties(2));

    @Test
    void 기본_한도는_분당_10이고_1보다_작으면_뜨지_않는다() {
        assertThat(new RateLimitProperties(10).write()).isEqualTo(10);
        assertThatThrownBy(() -> new RateLimitProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 한도까지는_통과하고_넘으면_다시_시도할_수_있을_때까지_남은_초를_담아_거절한다() {
        limiter.acquire(1L, RateLimitedApi.ORDER);
        limiter.acquire(1L, RateLimitedApi.ORDER);

        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.ORDER))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void API마다_회원마다_따로_센다() {
        limiter.acquire(1L, RateLimitedApi.ORDER);
        limiter.acquire(1L, RateLimitedApi.ORDER);

        assertThatCode(() -> limiter.acquire(1L, RateLimitedApi.CANCEL)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(2L, RateLimitedApi.ORDER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.ORDER))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void 주기가_지나면_다시_통과한다() throws InterruptedException {
        UserRateLimiter shortPeriod = new UserRateLimiter(new RateLimitProperties(1), Duration.ofMillis(300),
                System::nanoTime);
        shortPeriod.acquire(1L, RateLimitedApi.CANCEL);
        assertThatThrownBy(() -> shortPeriod.acquire(1L, RateLimitedApi.CANCEL))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(400);

        assertThatCode(() -> shortPeriod.acquire(1L, RateLimitedApi.CANCEL)).doesNotThrowAnyException();
    }

    @Test
    void 두_주기_넘게_쓰지_않은_회원의_카운터는_다음_주기에_메모리에서_지운다() {
        AtomicLong now = new AtomicLong();
        long minute = Duration.ofMinutes(1).toNanos();
        UserRateLimiter swept = new UserRateLimiter(new RateLimitProperties(2), Duration.ofMinutes(1), now::get);
        swept.acquire(1L, RateLimitedApi.ORDER);
        swept.acquire(2L, RateLimitedApi.ORDER);
        assertThat(swept.size()).isEqualTo(2);

        now.set(minute * 2);
        swept.acquire(2L, RateLimitedApi.ORDER);
        assertThat(swept.size()).as("2주기 전 사용은 아직 지우지 않는다(경계)").isEqualTo(2);

        now.set(minute * 3);
        swept.acquire(3L, RateLimitedApi.ORDER);
        assertThat(swept.size()).as("회원 1은 3분 동안 안 써서 지워진다").isEqualTo(2);
    }

    @Test
    void 쉬던_카운터를_쓰는_순간_다른_요청이_정리를_돌려도_방금_쓴_카운터는_지우지_않는다() throws Exception {
        AtomicLong now = new AtomicLong();
        long round = Duration.ofMinutes(3).toNanos();
        UserRateLimiter raced = new UserRateLimiter(new RateLimitProperties(1_000_000), Duration.ofMinutes(1),
                now::get);
        raced.acquire(1L, RateLimitedApi.ORDER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            for (int i = 1; i <= 2_000; i++) {
                // 회원 1의 카운터는 지난 회차(3분 전)에 쓰여 이제 쉬는 상태다. 회원 1이 쓰는 순간 회원 2의 요청이 정리를 돌린다
                now.set(round * i);
                Future<?> user = executor.submit(() -> {
                    start.await();
                    raced.acquire(1L, RateLimitedApi.ORDER);
                    return null;
                });
                Future<?> sweeper = executor.submit(() -> {
                    start.await();
                    raced.acquire(2L, RateLimitedApi.ORDER);
                    return null;
                });
                user.get(5, TimeUnit.SECONDS);
                sweeper.get(5, TimeUnit.SECONDS);

                assertThat(raced.contains(1L, RateLimitedApi.ORDER)).as("%d회차", i).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 거절_횟수를_API별_지표로_낸다_거절이_없어도_API마다_0으로_나온다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        limiter.bindTo(registry);
        limiter.acquire(1L, RateLimitedApi.CANCEL);
        limiter.acquire(1L, RateLimitedApi.CANCEL);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L, RateLimitedApi.CANCEL))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        assertThat(rejected(registry, "cancel")).isEqualTo(2);
        assertThat(rejected(registry, "order")).isZero();
        assertThat(registry.find(UserRateLimiter.REJECTED_METRIC).functionCounters())
                .hasSize(RateLimitedApi.values().length);
    }

    private static double rejected(SimpleMeterRegistry registry, String api) {
        return registry.get(UserRateLimiter.REJECTED_METRIC).tag("api", api).functionCounter().count();
    }
}
