package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 알림 시작 시각(ND-076, test-spec NT-KAF-04): 업무 시각이 기준보다 이르면 알림을 만들지 않는다. 같으면 만든다 */
class AlertStartPolicyTest {

    private static final Instant START = Instant.parse("2026-11-11T03:00:00Z");

    private final AlertStartPolicy policy = new AlertStartPolicy(START);

    @Test
    void 시작_시각보다_이른_사건은_알림을_만들지_않는다() {
        assertThat(policy.allows(START.minusMillis(1))).isFalse();
    }

    @Test
    void 시작_시각과_같거나_늦은_사건은_알림을_만든다() {
        assertThat(policy.allows(START)).isTrue();
        assertThat(policy.allows(START.plusSeconds(1))).isTrue();
    }

    @Test
    void 시작_시각이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> new AlertStartPolicy((Instant) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
