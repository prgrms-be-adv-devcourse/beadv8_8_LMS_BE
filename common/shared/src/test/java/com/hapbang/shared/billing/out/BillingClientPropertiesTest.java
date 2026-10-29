package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

// 지갑 내부 API Client 설정(hapbang.clients.billing.*)의 필수 값과 기본값 (#272, 회복성 #411)
class BillingClientPropertiesTest {

    @Test
    void base_url이_없으면_설정_생성이_실패한다() {
        assertThatThrownBy(() -> new BillingClientProperties(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hapbang.clients.billing.base-url");
        assertThatThrownBy(() -> new BillingClientProperties(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeout을_주지_않으면_2초와_5초다() {
        BillingClientProperties properties = new BillingClientProperties("http://localhost:8082", null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void 회복성_설정을_주지_않으면_CONTEXT_2장의_값이다() {
        BillingClientProperties.Resilience resilience =
                new BillingClientProperties("http://localhost:8082", null, null).resilience();

        assertThat(resilience.timeLimit()).isEqualTo(Duration.ofSeconds(8));
        assertThat(resilience.circuitBreaker()).isEqualTo(new BillingClientProperties.CircuitBreaker(
                20, 10, 50f, Duration.ofSeconds(4), 50f, Duration.ofSeconds(30), 3));
        assertThat(resilience.retry()).isEqualTo(
                new BillingClientProperties.Retry(3, Duration.ofMillis(200), 2.0, 0.2));
    }

    @Test
    void 앱_설정의_회복성_값으로_덮어쓰고_주지_않은_값은_기본값이다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "hapbang.clients.billing.base-url", "http://billing:8080",
                "hapbang.clients.billing.resilience.time-limit", "6s",
                "hapbang.clients.billing.resilience.circuit-breaker.open-duration", "1m",
                "hapbang.clients.billing.resilience.retry.max-attempts", "2")));

        BillingClientProperties properties = binder.bind("hapbang.clients.billing", BillingClientProperties.class)
                .get();

        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.resilience().timeLimit()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.resilience().circuitBreaker().openDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.resilience().circuitBreaker().slidingWindowSize()).isEqualTo(20);
        assertThat(properties.resilience().retry().maxAttempts()).isEqualTo(2);
        assertThat(properties.resilience().retry().initialWait()).isEqualTo(Duration.ofMillis(200));
    }
}
