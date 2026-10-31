package com.hapbang.lecture.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

// 배치 빈은 hapbang.scheduling.enabled=true일 때만 만들어진다. apps yaml을 읽지 않는 모듈 테스트에서는 꺼진다
class ConditionalOnSchedulingEnabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProbeConfig.class);

    @Test
    void 속성이_true면_배치_빈을_만든다() {
        runner.withPropertyValues("hapbang.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ProbeBatch.class));
    }

    @Test
    void 속성이_없으면_배치_빈을_만들지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ProbeBatch.class));
    }

    @Test
    void 속성이_false면_배치_빈을_만들지_않는다() {
        runner.withPropertyValues("hapbang.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ProbeBatch.class));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeBatch.class)
    static class ProbeConfig {
    }

    @ConditionalOnSchedulingEnabled
    static class ProbeBatch {
    }
}
