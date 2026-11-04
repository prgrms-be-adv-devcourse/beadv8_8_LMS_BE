package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code hapbang.chat.delivery.*}의 기본값은 {@link ChatDeliveryProperties} 코드가 원본이고, yaml·환경 변수로 덮을 수 있다
 * (설정 위치 정리). 값은 application.yaml에 있던 미확정 초기값(Q12)과 같다.
 */
class ChatDeliveryPropertiesTest {

    @Test
    void 아무것도_설정하지_않으면_코드_기본값을_쓴다() {
        ChatDeliveryProperties properties = bind(Map.of());

        // 운영 실시간 전파 구현과 합친 통합에서 기본으로 켠다
        assertThat(properties.pipeline().enabled()).isTrue();
        assertThat(properties.instanceId()).isNull();
        assertThat(properties.relay()).isEqualTo(new ChatDeliveryProperties.Relay(Duration.ofMillis(200), 50,
                Duration.ofSeconds(30), 10, new ChatDeliveryProperties.Backoff(Duration.ofSeconds(1),
                        Duration.ofSeconds(60), 0.2)));
        assertThat(properties.consumer()).isEqualTo(new ChatDeliveryProperties.Consumer(2, 1, 20,
                Duration.ofSeconds(2), 10, new ChatDeliveryProperties.Backoff(Duration.ofSeconds(1),
                        Duration.ofSeconds(60), 0.2)));
        assertThat(properties.reclaim()).isEqualTo(new ChatDeliveryProperties.Reclaim(Duration.ofSeconds(5),
                Duration.ofSeconds(30), 20, 100, 50));
        assertThat(properties.reconstruct()).isEqualTo(new ChatDeliveryProperties.Reconstruct(
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(5), 5, 100));
        assertThat(properties.trim()).isEqualTo(new ChatDeliveryProperties.Trim(Duration.ofMinutes(10),
                Duration.ofHours(24)));
        assertThat(properties.pause()).isEqualTo(new ChatDeliveryProperties.Pause(Duration.ofSeconds(1),
                Duration.ofSeconds(30)));
        assertThat(properties.shutdownAwait()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 설정한_키만_덮고_나머지는_기본값을_쓴다() {
        ChatDeliveryProperties properties = bind(Map.of(
                "hapbang.chat.delivery.relay.batch-size", "7",
                "hapbang.chat.delivery.pipeline.enabled", "false",
                "hapbang.chat.delivery.consumer.backoff.max", "5s"));

        assertThat(properties.relay().batchSize()).isEqualTo(7);
        assertThat(properties.relay().pollInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.pipeline().enabled()).isFalse();
        assertThat(properties.consumer().backoff().max()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.consumer().backoff().initial()).isEqualTo(Duration.ofSeconds(1));
    }

    private static ChatDeliveryProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("hapbang.chat.delivery", ChatDeliveryProperties.class);
    }
}
