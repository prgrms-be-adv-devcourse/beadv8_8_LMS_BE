package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 지갑 Client health는 회로가 닫히면 UP, 열렸거나 시험 중이면 UNKNOWN이고 DOWN이 되지 않는다 (#411)
class BillingClientHealthIndicatorTest {

    private final BillingApiClient client = new BillingApiClient(RestClient.builder(),
            new BillingClientProperties("http://127.0.0.1:9", null, null), "unit-internal-api-key");
    private final BillingClientHealthIndicator indicator = new BillingClientHealthIndicator(client);

    @Test
    void 회로가_닫혀_있으면_UP이다() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", CircuitBreaker.State.CLOSED);
    }

    @Test
    void 회로가_열렸거나_시험_중이면_UNKNOWN이다() {
        client.circuitBreaker().transitionToOpenState();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(indicator.health().getDetails()).containsEntry("state", CircuitBreaker.State.OPEN);

        client.circuitBreaker().transitionToHalfOpenState();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }
}
