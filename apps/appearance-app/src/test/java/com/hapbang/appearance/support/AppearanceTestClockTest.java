package com.hapbang.appearance.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * 테스트 시계 자체의 규칙 (#297). 기준 시계를 고정해 흐름 없이 확인한다.
 */
class AppearanceTestClockTest {

    private static final Instant BASE = Instant.parse("2026-10-16T00:00:00Z");

    private final AppearanceTestClock clock = new AppearanceTestClock(Clock.fixed(BASE, AppearanceTime.ZONE));

    @Test
    @DisplayName("앞당기지 않으면 기준 시계와 같고, 시간대는 한국 시간이다")
    void base() {
        assertThat(clock.instant()).isEqualTo(BASE);
        assertThat(clock.getZone()).isEqualTo(AppearanceTime.ZONE);
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 0));
    }

    @Test
    @DisplayName("advance는 앞당긴 만큼을 쌓는다 (1일 + 2시간)")
    void advance() {
        clock.advance(Duration.ofDays(1));
        clock.advance(Duration.ofHours(2));

        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 10, 17, 11, 0));
    }

    @Test
    @DisplayName("advanceTo는 목표 시각 1밀리초 뒤로 간다 (\"같거나 지나면\" 조건을 넘긴다). 이미 지난 목표면 그대로 둔다")
    void advanceTo() {
        LocalDateTime target = LocalDateTime.of(2026, 10, 23, 21, 0);

        clock.advanceTo(target);
        assertThat(clock.now()).isEqualTo(target.plusNanos(1_000_000));

        clock.advanceTo(target.minusDays(1));
        assertThat(clock.now()).isEqualTo(target.plusNanos(1_000_000));
    }

    @Test
    @DisplayName("뒤로는 돌릴 수 없다 (이미 기록한 시각보다 과거가 되므로)")
    void noBackward() {
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reset하면 앞당김이 없어진다")
    void reset() {
        clock.advance(Duration.ofDays(7));

        clock.reset();

        assertThat(clock.instant()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("시간대만 바꾼 시계도 원래 시계의 앞당김을 따라간다")
    void withZoneFollowsOffset() {
        Clock utc = clock.withZone(ZoneOffset.UTC);

        clock.advance(Duration.ofHours(3));

        assertThat(utc.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(utc.instant()).isEqualTo(BASE.plus(Duration.ofHours(3)));
        assertThat(utc.withZone(ZoneId.of("Asia/Seoul")).instant()).isEqualTo(clock.instant());
    }
}
