package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// base-url이 있을 때만 지갑 Client와 지표·health 빈이 함께 생기고, 지표에 name=billing이 붙는다 (#411)
class BillingClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(BillingApiClient.class, BillingClientMetrics.class,
                    BillingClientHealthIndicator.class)
            .withPropertyValues("hapbang.internal-api.key=unit-internal-api-key");

    @Test
    void base_url이_있으면_Client와_지표와_health가_생긴다() {
        runner.withPropertyValues("hapbang.clients.billing.base-url=http://127.0.0.1:9").run(context -> {
            assertThat(context).hasSingleBean(BillingApiClient.class)
                    .hasSingleBean(BillingClientMetrics.class)
                    .hasSingleBean(BillingClientHealthIndicator.class);

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(BillingClientMetrics.class).bindTo(registry);
            assertThat(registry.find("resilience4j.circuitbreaker.state").tag("name", "billing").gauges())
                    .isNotEmpty();
            assertThat(registry.find("resilience4j.retry.calls").tag("name", "billing").functionCounters())
                    .isNotEmpty();
        });
    }

    @Test
    void base_url이_없으면_아무_빈도_생기지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BillingApiClient.class)
                .doesNotHaveBean(BillingClientMetrics.class)
                .doesNotHaveBean(BillingClientHealthIndicator.class));
    }
}
