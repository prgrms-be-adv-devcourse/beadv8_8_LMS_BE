package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * 챗봇 LLM 호출 공통 설정 {@code hapbang.chatbot.llm} (#718 공통 기반). FAQ 대화·강의 추천 설명·인플루언서 추천·자유 질문이 함께 쓴다.
 *
 * @param maxConcurrentCalls 동시에 진행할 수 있는 LLM 호출 수(앱 전체, 모든 기능 합계). 넘으면 각 기능이 LLM 없이 답한다
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.llm")
public record ChatbotLlmProperties(
        @DefaultValue("4") @Positive int maxConcurrentCalls) {
}
