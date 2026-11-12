package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;

/** 챗봇 LLM 호출 공통 빈(#718 공통 기반, #715 FAQ 설정에서 옮김). 호출 장치는 {@code LlmCallGuard} */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChatbotLlmProperties.class, ChatbotRecommendLlmProperties.class, ChatbotChatProperties.class})
public class ChatbotLlmConfig {

    /** LLM 회로 빈 이름 */
    public static final String CIRCUIT_BREAKER = "chatbotLlmCircuitBreaker";

    /**
     * LLM 호출 회로(모든 챗봇 기능이 함께 쓴다). 최근 10번 중 절반이 실패(시간 초과 포함)하면 30초 동안 LLM을 부르지 않고
     * 각 기능이 fallback으로 답한다. 30초 뒤 3번을 시험해 보고 닫거나 다시 연다. 운영 OpenAI 장애가 길어져도 요청마다 시간 상한까지
     * 기다리지 않게 한다. 상태가 바뀔 때만 로그를 남긴다(열림 WARN, 그 밖 INFO. docs/dev/logging.md).
     * 열린 동안의 요청은 지표 {@code fallbacks{reason=circuit_open}}로 본다.
     */
    @Bean(name = CIRCUIT_BREAKER)
    CircuitBreaker chatbotLlmCircuitBreaker() {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("chatbot-llm", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                log.warn("LLM 회로가 열려 챗봇 LLM 호출을 fallback으로 답한다 transition={}", event.getStateTransition());
            } else {
                log.info("LLM 회로 상태가 바뀌었다 transition={}", event.getStateTransition());
            }
        });
        return circuitBreaker;
    }
}
