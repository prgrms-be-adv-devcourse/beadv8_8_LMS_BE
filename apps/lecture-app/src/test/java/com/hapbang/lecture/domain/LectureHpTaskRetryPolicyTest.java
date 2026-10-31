package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// HP 작업 재시도 간격과 최대 횟수 (D57 (6)·(7))
class LectureHpTaskRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    private final LectureHpTaskRetryPolicy policy = new LectureHpTaskRetryPolicy(
            Duration.ofMinutes(1), Duration.ofHours(1), 10, 0.2);

    @Test
    void 지수_백오프는_1분부터_두_배씩_늘고_1시간을_넘지_않는다() {
        assertThat(policy.nextAttemptAt(1, NOW, 0)).isEqualTo(NOW.plusMinutes(1));
        assertThat(policy.nextAttemptAt(2, NOW, 0)).isEqualTo(NOW.plusMinutes(2));
        assertThat(policy.nextAttemptAt(3, NOW, 0)).isEqualTo(NOW.plusMinutes(4));
        assertThat(policy.nextAttemptAt(7, NOW, 0)).isEqualTo(NOW.plusHours(1));
        assertThat(policy.nextAttemptAt(40, NOW, 0)).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void 지터는_간격의_20퍼센트_안이다() {
        assertThat(policy.nextAttemptAt(3, NOW, 0.5)).isEqualTo(NOW.plusMinutes(4).plusSeconds(24));
        assertThat(policy.nextAttemptAt(3, NOW, 0.999)).isBefore(NOW.plusMinutes(4).plusSeconds(48));
    }

    @Test
    void 열_번째_시도에서_한도에_닿는다() {
        assertThat(policy.isExhausted(9)).isFalse();
        assertThat(policy.isExhausted(10)).isTrue();
    }

    @Test
    void 잘못된_설정은_만들_수_없다() {
        assertThatThrownBy(() -> new LectureHpTaskRetryPolicy(Duration.ZERO, Duration.ofHours(1), 10, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LectureHpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LectureHpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
