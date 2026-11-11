package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 알림 시작 시각 설정 검증(ND-076·ND-088, test-spec NT-KAF-04): 값이 없거나 ISO-8601 시각으로 읽을 수 없으면 컨텍스트(앱 기동)가
 * 실패하고, 올바른 값이면 그 시각을 그대로 쓴다. 기본값을 두지 않으므로 다른 곳에서 값을 만들어 넣지 않는다.
 */
class AlertStartPolicyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AlertStartPolicy.class);

    @Test
    void 값이_없으면_기동에_실패한다() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 시각으로_읽을_수_없는_값이면_기동에_실패한다() {
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=2026-11-11 13:00")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 운영과_같은_형식의_값은_그_시각을_그대로_쓴다() {
        runner.withPropertyValues(AlertStartPolicy.PROPERTY + "=2026-11-11T13:00:00+09:00")
                .run(context -> assertThat(context.getBean(AlertStartPolicy.class).startAt())
                        .isEqualTo(Instant.parse("2026-11-11T04:00:00Z")));
    }
}
