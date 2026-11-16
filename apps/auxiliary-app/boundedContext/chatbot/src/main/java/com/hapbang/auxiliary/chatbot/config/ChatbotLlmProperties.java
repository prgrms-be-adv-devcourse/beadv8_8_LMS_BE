package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 챗봇 LLM 호출 공통 설정 {@code hapbang.chatbot.llm} (#718 공통 기반). FAQ 대화·강의 추천 설명·인플루언서 추천·자유 질문이 함께 쓴다.
 *
 * @param maxConcurrentCalls 동시에 진행할 수 있는 LLM 호출 수(앱 전체, 모든 기능 합계). 넘으면 각 기능이 LLM 없이 답한다
 * @param circuitBreaker     LLM 호출 회로(모든 기능이 함께 쓴다)
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.llm")
public record ChatbotLlmProperties(
        @DefaultValue("4") @Positive int maxConcurrentCalls,
        @DefaultValue @Valid CircuitBreakerSettings circuitBreaker) {

    /**
     * LLM 호출 회로. 최근 {@code slidingWindowSize}번(최소 {@code minimumNumberOfCalls}번) 중 {@code failureRateThreshold}% 이상이
     * 실패(시간 초과 포함)하면 {@code waitDurationInOpenState} 동안 LLM을 부르지 않고, 그 뒤 {@code permittedNumberOfCallsInHalfOpenState}번을
     * 시험해 보고 닫거나 다시 연다.
     */
    public record CircuitBreakerSettings(
            @DefaultValue("10") @Positive int slidingWindowSize,
            @DefaultValue("5") @Positive int minimumNumberOfCalls,
            @DefaultValue("50") @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") float failureRateThreshold,
            @DefaultValue("30s") @NotNull Duration waitDurationInOpenState,
            @DefaultValue("3") @Positive int permittedNumberOfCallsInHalfOpenState) {
    }
}
