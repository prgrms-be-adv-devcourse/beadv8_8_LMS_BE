package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

// 매진 표시 메모리 캐시의 재사용 판정: readAt ≤ now < readAt + TTL
class SoldOutSignalTest {

    private static final Instant READ_AT = Instant.parse("2026-11-08T03:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(1);
    private static final SoldOutSignal SIGNAL = new SoldOutSignal(true, READ_AT);

    @Test
    void TTL_안이면_재사용한다() {
        assertThat(SIGNAL.freshAt(READ_AT, TTL)).isTrue();
        assertThat(SIGNAL.freshAt(READ_AT.plus(TTL).minusNanos(1), TTL)).isTrue();
    }

    @Test
    void TTL이_지나면_다시_읽는다() {
        assertThat(SIGNAL.freshAt(READ_AT.plus(TTL), TTL)).isFalse();
    }

    @Test
    void TTL이_0이면_언제나_다시_읽는다() {
        assertThat(SIGNAL.freshAt(READ_AT, Duration.ZERO)).isFalse();
    }

    @Test
    void 시계가_읽은_시각보다_앞으로_돌아가면_다시_읽는다() {
        assertThat(SIGNAL.freshAt(READ_AT.minusMillis(1), TTL)).isFalse();
    }
}
