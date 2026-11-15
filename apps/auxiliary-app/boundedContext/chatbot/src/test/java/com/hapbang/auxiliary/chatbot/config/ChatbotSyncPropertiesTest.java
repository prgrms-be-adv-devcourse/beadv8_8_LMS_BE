package com.hapbang.auxiliary.chatbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 싱크 설정 {@code hapbang.chatbot.sync} (#716, #717). 범위를 벗어난 값이면 앱이 뜨지 않는다 */
class ChatbotSyncPropertiesTest {

    private static final String PREFIX = "hapbang.chatbot.sync.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SyncPropertiesConfig.class);

    @Test
    void 설정이_없으면_기본값으로_뜬다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ChatbotSyncProperties properties = context.getBean(ChatbotSyncProperties.class);
            assertThat(properties.pageSize()).isEqualTo(500);
            assertThat(properties.warningRatio()).isEqualTo(0.3);
            assertThat(properties.warningMinChanged()).isEqualTo(10);
            assertThat(properties.mismatchMaxRows()).isEqualTo(100);
            assertThat(properties.mismatchKeepRuns()).isEqualTo(168);
            assertThat(properties.runRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.failureAlertAfter()).isEqualTo(Duration.ofHours(3));
        });
    }

    @Test
    void 경계값은_받는다() {
        runner.withPropertyValues(
                        PREFIX + "page-size=1",
                        PREFIX + "warning-ratio=1",
                        PREFIX + "warning-min-changed=0",
                        PREFIX + "mismatch-max-rows=0",
                        PREFIX + "mismatch-keep-runs=1",
                        PREFIX + "run-retention=1d",
                        PREFIX + "failure-alert-after=1s")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PREFIX + "page-size=500")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "page-size=0",
            "page-size=501",
            "warning-ratio=0",
            "warning-ratio=1.01",
            "warning-min-changed=-1",
            "mismatch-max-rows=-1",
            "mismatch-keep-runs=0",
            "run-retention=23h",
            "failure-alert-after=0s",
            "failure-alert-after=-1s"
    })
    void 범위를_벗어난_값이면_기동에_실패한다(String property) {
        runner.withPropertyValues(PREFIX + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatbotSyncProperties.class)
    static class SyncPropertiesConfig {
    }
}
