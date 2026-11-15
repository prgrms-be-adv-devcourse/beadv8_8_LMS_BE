package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 추천 버튼 경로의 LLM 설정 {@code hapbang.chatbot.recommend} (#720). 강의·인플루언서가 같은 값을 쓴다(리랭킹·쿼리 확장 처리가 한 벌).
 * <p>
 * 시간 상한은 근거 없는 출발값이다. 버튼 경로 전체(쿼리 확장 + 임베딩 + 검색 + 리랭킹)가 Gateway 30초 안에 끝나도록 잡았고,
 * 직접 재서 고친다. 켤지·모델은 리랭킹 평가(#867)에서 정한다.
 *
 * @param rerank         리랭킹 + 이유(LLM 한 번)
 * @param queryExpansion 관심사 쿼리 확장(LLM 한 번, 관심사가 있을 때만)
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.recommend")
public record ChatbotRecommendLlmProperties(
        @DefaultValue @Valid Rerank rerank,
        @DefaultValue @Valid QueryExpansion queryExpansion) {

    /**
     * @param enabled         켜면 LLM이 풀에서 고르고 이유를 쓴다. 끄면 코드 순서 상위 추천 수만 보여 주고 이유만 받는다
     * @param model           모델 이름. 비우면 공급자 기본 모델
     * @param timeout         시간 상한. 넘으면 코드 순서 상위 추천 수 + 이유 코드 문장
     * @param poolSize        LLM에 보여 줄 맞춤 후보 수(풀). 풀에는 맞춤 후보만 넣는다
     * @param reasonMaxLength 이유 문장의 최대 글자 수(카드 크기). 넘으면 자른다
     * @param descriptionMaxLength 프롬프트에 넣는 강의 소개·요청 회원 자기소개의 최대 글자 수(입력 토큰 상한)
     * @param taste           요청 회원의 취향을 프롬프트에 넣는 범위(#948). 기본은 넣지 않는다
     * @param temperature     리랭킹 호출의 온도. 비우면 공급자 설정(prod 0.2)을 쓴다. 리랭킹은 고르기라 흔들림의 이득이 작아 따로 둔다(#948)
     */
    public record Rerank(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String model,
            @DefaultValue("12s") @NotNull Duration timeout,
            @DefaultValue("20") @Positive int poolSize,
            @DefaultValue("80") @Positive int reasonMaxLength,
            @DefaultValue("300") @Positive int descriptionMaxLength,
            @DefaultValue("NONE") @NotNull Taste taste,
            @Nullable @DecimalMin("0.0") @DecimalMax("2.0") Double temperature) {
    }

    /** 리랭킹 프롬프트에 넣는 요청 회원 취향의 범위(#948) */
    @Getter
    @RequiredArgsConstructor
    public enum Taste {

        NONE("넣지 않는다. LLM은 후보 정보만 보고 고른다(#720)"),
        PUBLIC("본인이 이번에 입력한 관심사와, 인플루언서면 공개 프로필의 분야 이름·자기소개. 넣을 것이 없으면 리랭킹하지 않고 코드 순서로 이유만 쓴다");

        private final String description;
    }

    /**
     * @param model         모델 이름. 비우면 공급자 기본 모델(짧은 출력이라 작은 모델로 충분하다)
     * @param timeout       시간 상한. 넘으면 관심사 원문으로 계속한다
     * @param maxExpansions 원문 말고 더 받는 표현 수. 원문과 함께 임베딩해 평균을 낸다
     */
    public record QueryExpansion(
            @DefaultValue("") String model,
            @DefaultValue("5s") @NotNull Duration timeout,
            @DefaultValue("3") @PositiveOrZero int maxExpansions) {
    }
}
