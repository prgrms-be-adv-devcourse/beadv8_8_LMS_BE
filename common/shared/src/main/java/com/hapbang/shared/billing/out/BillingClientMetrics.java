package com.hapbang.shared.billing.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 지갑 Client의 Circuit Breaker 상태·호출 결과와 Retry 횟수를 Micrometer 지표로 낸다 (#411).
 * Client 안의 재시도는 호출한 쪽 시도 기록에 남지 않으므로 지표로만 본다. 지표 이름은 Resilience4j 기본값
 * ({@code resilience4j.circuitbreaker.*}, {@code resilience4j.retry.calls})이고 {@code name=billing} 태그가 붙는다.
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.billing", name = "base-url")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class BillingClientMetrics implements MeterBinder {

    private final BillingApiClient billingApiClient;

    public BillingClientMetrics(BillingApiClient billingApiClient) {
        this.billingApiClient = billingApiClient;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(billingApiClient.circuitBreakerRegistry())
                .bindTo(registry);
        TaggedRetryMetrics.ofRetryRegistry(billingApiClient.retryRegistry()).bindTo(registry);
    }
}
