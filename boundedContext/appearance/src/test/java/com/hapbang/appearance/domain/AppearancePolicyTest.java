package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppearancePolicyTest {

    private final AppearancePolicy policy = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2);

    @Test
    @DisplayName("최저 출연료보다 적으면 미달로 판단한다")
    void isBelowMinimumFee() {
        assertThat(policy.isBelowMinimumFee(19_999)).isTrue();
        assertThat(policy.isBelowMinimumFee(20_000)).isFalse();
    }

    @Test
    @DisplayName("위약금은 출연료의 30%이고 원 단위 미만은 버린다")
    void calculatePenalty() {
        assertThat(policy.calculatePenalty(20_000)).isEqualTo(6_000);
        assertThat(policy.calculatePenalty(25_555)).isEqualTo(7_666); // 7,666.5 → 7,666
        assertThat(policy.calculatePenalty(0)).isZero();
    }

    @Test
    @DisplayName("출연 확인 마감은 방송 예정 일시 + 7×24시간이다")
    void confirmDeadline() {
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 10, 20, 21, 30);

        assertThat(policy.confirmDeadline(scheduledAt)).isEqualTo(LocalDateTime.of(2026, 10, 27, 21, 30));
    }

    @Test
    @DisplayName("출연 취소 요청은 최대 2회까지 할 수 있다")
    void canRequestCancel() {
        assertThat(policy.canRequestCancel(0)).isTrue();
        assertThat(policy.canRequestCancel(1)).isTrue();
        assertThat(policy.canRequestCancel(2)).isFalse();
    }

    @Test
    @DisplayName("잘못된 정책 값으로는 만들 수 없다")
    void invalidPolicy() {
        assertThatThrownBy(() -> new AppearancePolicy(0, 30, Duration.ofDays(7), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppearancePolicy(20_000, 101, Duration.ofDays(7), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppearancePolicy(20_000, 30, Duration.ZERO, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppearancePolicy(20_000, 30, Duration.ofDays(7), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
