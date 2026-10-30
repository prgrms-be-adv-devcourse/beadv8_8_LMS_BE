package com.hapbang.chat.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.chat.in.websocket.ChatInboundReceiveBudget;
import com.hapbang.chat.in.websocket.ChatOutboundSendBudget;
import com.hapbang.chat.in.websocket.ChatWebSocketSessionRegistry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 실시간 전달({@code hapbang.chat.realtime.*})과 전달 파이프라인 사용 여부의 기본값은 코드에 있고(application.yaml에 두지 않는다),
 * yaml·환경 변수가 같은 키로 덮는다. 사용 여부는 둘 다 설정이 없으면 켜지고 {@code false}로 끈다.
 */
class ChatRealtimeSettingsTest {

    private final ApplicationContextRunner conditions = new ApplicationContextRunner()
            .withUserConfiguration(RealtimeOnly.class, PipelineOnly.class);

    // Boot 앱처럼 "30s"·"512KB" 같은 값을 @Value로 바꾸는 변환 서비스를 쓴다
    private final ApplicationContextRunner budgets = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(ChatWebSocketSessionRegistry.class, () -> mock(ChatWebSocketSessionRegistry.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ChatInboundReceiveBudget.class)
            .withBean(ChatOutboundSendBudget.class);

    @Test
    void 아무것도_설정하지_않으면_실시간_전달과_파이프라인이_켜진다() {
        conditions.run(context -> assertThat(context).hasNotFailed()
                .hasBean("realtimeMarker")
                .hasBean("pipelineMarker"));
    }

    @Test
    void yaml_값으로_실시간_전달과_파이프라인을_끈다() {
        conditions.withPropertyValues(ConditionalOnChatRealtime.REALTIME_ENABLED + "=false",
                        "hapbang.chat.delivery.pipeline.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean("realtimeMarker")
                        .doesNotHaveBean("pipelineMarker"));
    }

    @Test
    void 환경_변수로_실시간_전달과_파이프라인을_끈다() {
        conditions.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of(
                                "HAPBANG_CHAT_REALTIME_ENABLED", "false",
                                "HAPBANG_CHAT_DELIVERY_PIPELINE_ENABLED", "false"))))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean("realtimeMarker")
                        .doesNotHaveBean("pipelineMarker"));
    }

    @Test
    void 아무것도_설정하지_않으면_실시간_수치는_코드_기본값을_쓴다() {
        budgets.run(context -> {
            assertThat(context).hasNotFailed();
            ChatInboundReceiveBudget inbound = context.getBean(ChatInboundReceiveBudget.class);
            assertThat(inbound.executor().getCorePoolSize()).isEqualTo(8);
            assertThat(inbound.executor().getQueueCapacity()).isEqualTo(1000);
            assertThat(ReflectionTestUtils.getField(inbound, "perSessionFrames")).isEqualTo(64);
            assertThat(ReflectionTestUtils.getField(inbound, "closedSessionTtl")).isEqualTo(Duration.ofSeconds(30));
            assertThat(ReflectionTestUtils.getField(inbound, "maxClosedSessions")).isEqualTo(10_000);
            ChatOutboundSendBudget outbound = context.getBean(ChatOutboundSendBudget.class);
            assertThat(outbound.executor().getCorePoolSize()).isEqualTo(16);
            assertThat(outbound.executor().getQueueCapacity()).isEqualTo(10_000);
        });
    }

    @Test
    void yaml_값이_실시간_수치의_코드_기본값을_덮는다() {
        budgets.withPropertyValues("hapbang.chat.realtime.inbound.per-session-frames=32",
                        "hapbang.chat.realtime.outbound.workers=4")
                .run(context -> {
                    assertThat(ReflectionTestUtils.getField(context.getBean(ChatInboundReceiveBudget.class),
                            "perSessionFrames")).isEqualTo(32);
                    assertThat(context.getBean(ChatOutboundSendBudget.class).executor().getCorePoolSize())
                            .isEqualTo(4);
                    // 덮지 않은 키는 코드 기본값 그대로다
                    assertThat(context.getBean(ChatInboundReceiveBudget.class).executor().getCorePoolSize())
                            .isEqualTo(8);
                });
    }

    @Test
    void 환경_변수가_실시간_수치의_코드_기본값을_덮는다() {
        budgets.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of(
                                "HAPBANG_CHAT_REALTIME_INBOUND_PER_SESSION_FRAMES", "16"))))
                .run(context -> assertThat(ReflectionTestUtils.getField(
                        context.getBean(ChatInboundReceiveBudget.class), "perSessionFrames")).isEqualTo(16));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnChatRealtime
    static class RealtimeOnly {

        @Bean
        String realtimeMarker() {
            return "realtime";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnChatDeliveryPipeline
    static class PipelineOnly {

        @Bean
        String pipelineMarker() {
            return "pipeline";
        }
    }
}
