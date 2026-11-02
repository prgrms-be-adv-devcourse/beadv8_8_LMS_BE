package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HpTaskRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final HpTaskRetryPolicy POLICY =
            new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 10, 0.2);

    @Test
    @DisplayName("간격은 1분부터 두 배씩 늘고(1·2·4·8·16·32분) 1시간에서 멈춘다 (지터 0)")
    void exponentialBackoff() {
        assertThat(POLICY.nextAttemptAt(1, NOW, 0)).isEqualTo(NOW.plusMinutes(1));
        assertThat(POLICY.nextAttemptAt(2, NOW, 0)).isEqualTo(NOW.plusMinutes(2));
        assertThat(POLICY.nextAttemptAt(3, NOW, 0)).isEqualTo(NOW.plusMinutes(4));
        assertThat(POLICY.nextAttemptAt(6, NOW, 0)).isEqualTo(NOW.plusMinutes(32));
        assertThat(POLICY.nextAttemptAt(7, NOW, 0)).isEqualTo(NOW.plusHours(1));
        assertThat(POLICY.nextAttemptAt(40, NOW, 0)).isEqualTo(NOW.plusHours(1));
    }

    @Test
    @DisplayName("지터는 간격의 최대 20%를 더한다 (난수 0.5면 10%)")
    void jitter() {
        assertThat(POLICY.nextAttemptAt(1, NOW, 0.5)).isEqualTo(NOW.plusSeconds(66));
        assertThat(POLICY.nextAttemptAt(7, NOW, 0.5)).isEqualTo(NOW.plusMinutes(66));
    }

    @Test
    @DisplayName("10번째 시도까지 실패하면 다 쓴 것이다")
    void exhausted() {
        assertThat(POLICY.isExhausted(9)).isFalse();
        assertThat(POLICY.isExhausted(10)).isTrue();
    }

    @Test
    @DisplayName("잘못된 값(기본 간격 0, 최대 < 기본, 횟수 0, 지터 1 초과)으로는 만들 수 없다")
    void invalid() {
        assertThatThrownBy(() -> new HpTaskRetryPolicy(Duration.ZERO, Duration.ofHours(1), 10, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HpTaskRetryPolicy(Duration.ofMinutes(5), Duration.ofMinutes(1), 10, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
