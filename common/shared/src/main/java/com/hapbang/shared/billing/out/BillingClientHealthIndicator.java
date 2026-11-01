package com.hapbang.shared.billing.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 지갑 Client Circuit Breaker 상태 ({@code /actuator/health}의 {@code billingClient}, #411).
 * 회로가 닫혀 있으면 UP, 열렸거나 시험 중이면 UNKNOWN이다. DOWN으로 두지 않아 지갑 장애가 호출하는 앱의 장애로
 * 보이지 않는다. liveness·readiness 그룹에도 넣지 않는다(지갑이 내려가도 강의·출연 pod는 트래픽을 받는다).
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.billing", name = "base-url")
@ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
public class BillingClientHealthIndicator implements HealthIndicator {

    private final BillingApiClient billingApiClient;

    public BillingClientHealthIndicator(BillingApiClient billingApiClient) {
        this.billingApiClient = billingApiClient;
    }

    @Override
    public Health health() {
        CircuitBreaker circuitBreaker = billingApiClient.circuitBreaker();
        CircuitBreaker.State state = circuitBreaker.getState();
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        Health.Builder builder = state == CircuitBreaker.State.CLOSED ? Health.up() : Health.unknown();
        return builder
                .withDetail("state", state)
                .withDetail("failureRate", metrics.getFailureRate())
                .withDetail("slowCallRate", metrics.getSlowCallRate())
                .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
                .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
                .withDetail("slowCalls", metrics.getNumberOfSlowCalls())
                .withDetail("notPermittedCalls", metrics.getNumberOfNotPermittedCalls())
                .build();
    }
}
