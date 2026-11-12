package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 평점 전체 평균 캐시의 동시 요청(#883): 다시 구하는 동안 다른 요청은 기다리지 않는다. 주기·실패 대기는 {@code RatingBaselineTest}가 본다 */
class CachedRatingBaselineTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));

    @Test
    @DisplayName("주기가 지나 한 스레드가 다시 구하는 동안, 다른 요청은 기다리지 않고 가진 값을 바로 쓴다")
    void othersUseStaleValueWhileRefreshing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Supplier<Double> source = () -> {
            if (calls.incrementAndGet() == 1) {
                return 4.0;
            }
            entered.countDown();
            await(release);
            return 4.5;
        };
        CachedRatingBaseline baseline = new CachedRatingBaseline(source, Duration.ofMinutes(10), clock);
        assertThat(baseline.get()).isEqualTo(4.0);
        clock.advance(Duration.ofMinutes(10));

        CompletableFuture<Double> refresher = CompletableFuture.supplyAsync(baseline::get);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(baseline.get()).isEqualTo(4.0);
        release.countDown();

        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEqualTo(4.5);
        assertThat(baseline.get()).isEqualTo(4.5);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("아직 값이 없는데 다른 스레드가 처음 값을 구하는 중이면 기다리지 않고 바로 실패한다")
    void failsFastWhileFirstRefresh() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CachedRatingBaseline baseline = new CachedRatingBaseline(() -> {
            entered.countDown();
            await(release);
            return 4.2;
        }, Duration.ofMinutes(10), clock);

        CompletableFuture<Double> refresher = CompletableFuture.supplyAsync(baseline::get);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(baseline::get).isInstanceOf(IllegalStateException.class);
        release.countDown();

        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEqualTo(4.2);
        assertThat(baseline.get()).isEqualTo(4.2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
