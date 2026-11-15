package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * FAQ 챗봇 설정(#715). LLM 동시 호출 상한은 앱 전체 공통({@link ChatbotLlmProperties}). 공급자(임베딩 모델)마다 값이 달라 프로필 파일에서 정한다(local=Ollama, prod=OpenAI).
 *
 * @param indexName           Elasticsearch index 이름. 공급자·차원을 넣는다(예: {@code chatbot-faq-openai-1536}). 모델을 바꾸면 새 index에 다시 색인한다
 * @param dimensions          임베딩 차원. 모델과 맞아야 한다
 * @param similarityThreshold 이 값 아래(코사인 유사도)의 검색 결과는 버린다. 공급자마다 평가셋으로 정한다
 * @param topK                검색 결과 수
 * @param documentPrefix      문서를 임베딩할 때 앞에 붙이는 글(nomic-embed-text는 {@code search_document: })
 * @param queryPrefix         질문을 임베딩할 때 앞에 붙이는 글(nomic-embed-text는 {@code search_query: })
 * @param chatTimeout         한 번에 받는 대화의 LLM 응답 상한. 넘으면 fallback으로 답한다
 * @param streamTimeout       스트림 대화 전체 상한. Gateway 스트림 route(60s)·MVC 비동기 상한보다 짧아야 fallback·오류가 먼저 나간다
 * @param syncOnStartup       시작할 때 시드를 맞추고 색인한다(테스트는 끄고 직접 부른다)
 * @param indexCheckInterval  색인 점검 주기. 실패한 색인을 다시 한다(배치를 켠 실행만)
 * @param rateLimit           회원별 호출 제한
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.faq")
public record ChatbotFaqProperties(
        @NotBlank String indexName,
        @Positive int dimensions,
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
        @DefaultValue("3") @Positive int topK,
        @DefaultValue("") String documentPrefix,
        @DefaultValue("") String queryPrefix,
        @DefaultValue("20s") @NotNull Duration chatTimeout,
        @DefaultValue("45s") @NotNull Duration streamTimeout,
        @DefaultValue("true") boolean syncOnStartup,
        @DefaultValue("10m") @NotNull Duration indexCheckInterval,
        @DefaultValue @Valid RateLimit rateLimit) {

    /**
     * @param limit   한 주기에 회원 한 명이 부를 수 있는 횟수(FAQ 검색·대화 합계)
     * @param period  주기
     * @param maxKeys 메모리에 둘 회원 수 상한
     */
    public record RateLimit(
            @DefaultValue("10") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
