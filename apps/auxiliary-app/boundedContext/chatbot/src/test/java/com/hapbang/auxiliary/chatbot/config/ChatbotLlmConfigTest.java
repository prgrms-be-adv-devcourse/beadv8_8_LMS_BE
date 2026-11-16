package com.hapbang.auxiliary.chatbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/** 챗봇 LLM 회로 {@link ChatbotLlmConfig#CIRCUIT_BREAKER}. 설정이 없을 때의 값이 운영값이다 */
class ChatbotLlmConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatbotLlmConfig.class);

    @Test
    void 설정이_없으면_최근_10번_중_절반_실패로_30초_열고_3번_시험한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CircuitBreakerConfig config = context.getBean(ChatbotLlmConfig.CIRCUIT_BREAKER, CircuitBreaker.class)
                    .getCircuitBreakerConfig();
            assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        });
    }
}
