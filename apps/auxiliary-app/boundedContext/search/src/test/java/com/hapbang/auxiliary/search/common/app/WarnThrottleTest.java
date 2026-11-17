package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WarnThrottleTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);

    @Test
    @DisplayName("처음 실패에 남기고, 간격 안의 실패는 세기만 하고, 간격이 지난 실패에 그 사이 건수와 함께 남긴다")
    void throttles() {
        AtomicLong clock = new AtomicLong(1_000);
        WarnThrottle throttle = new WarnThrottle(INTERVAL, clock::get);

        assertThat(throttle.fail()).isEqualTo(1);

        clock.addAndGet(INTERVAL.toNanos() - 1);
        assertThat(throttle.fail()).isZero();
        assertThat(throttle.fail()).isZero();

        clock.addAndGet(1);
        assertThat(throttle.fail()).isEqualTo(3);
        assertThat(throttle.fail()).isZero();
    }

    @Test
    @DisplayName("여러 스레드가 같은 때 실패해도 한 번만 남긴다")
    void warnsOnceUnderContention() {
        WarnThrottle throttle = new WarnThrottle(INTERVAL, () -> 1_000);

        long warned = IntStream.range(0, 1_000).parallel().mapToLong(i -> throttle.fail()).filter(n -> n > 0).count();

        assertThat(warned).isEqualTo(1);
    }

    @Test
    @DisplayName("검색어 제안은 대상마다 따로 센다(한 대상이 계속 실패해도 다른 대상의 첫 실패를 남긴다)")
    void perTarget() {
        assertThat(KeywordCorrection.warnThrottle("lecture")).isSameAs(KeywordCorrection.warnThrottle("lecture"))
                .isNotSameAs(KeywordCorrection.warnThrottle("influencer"));
    }
}
