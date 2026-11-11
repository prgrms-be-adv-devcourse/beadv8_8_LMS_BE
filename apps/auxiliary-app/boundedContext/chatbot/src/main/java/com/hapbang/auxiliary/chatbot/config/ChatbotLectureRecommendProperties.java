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
 * 강의 추천 설정 {@code hapbang.chatbot.recommend.lecture} (#718). 정책 숫자(가중치·상한·혼합 비율)는 모두 여기 둔다.
 * index 이름·차원은 공통 {@link ChatbotIndexProperties}에 있다. 정합 점검 시각(cron)은 배치 빈의 {@code @Scheduled}
 * 플레이스홀더({@code index-check-cron})에 있다(싱크 배치와 같다).
 *
 * @param resultSize       추천 수
 * @param maxPerSeller     판매자 한 명의 강의를 최대 몇 개까지 넣는지(다양성)
 * @param signalLimit      취향 벡터에 쓰는 신호 강의 수(최근 순)
 * @param weights          신호 가중치
 * @param interestWeight   신호와 관심사가 함께 있을 때 관심사의 비율 α. {@code (1−α)·정규화(신호) + α·정규화(관심사)}.
 *                         활동 신호보다 낮게 둔다
 * @param popularityWeight 최종 점수에서 인기의 비율 β. {@code (1−β)·유사도 + β·인기(후보 안 0~1)}. 인기가 뒤집을 수 있는 유사도 차이는
 *                         약 {@code β/(1−β)}다(0.05면 약 0.05). 초기값은 식에서 정한 추정값이라 임베딩 유사도 분포를 보고 다시 정한다
 * @param knnK             벡터 검색으로 받는 후보 수
 * @param numCandidates    벡터 검색이 샤드마다 살펴볼 후보 수(knnK 이상)
 * @param popularPoolSize  인기순으로 채울 때 미리 읽는 강의 수
 * @param explainTimeout   버튼 경로의 LLM 이유 문장 시간 상한. 넘으면 정해진 문장으로 답한다
 * @param reasonMaxLength  LLM 이유 문장의 최대 글자 수. 넘으면 자른다(카드 크기)
 * @param explainDescriptionMaxLength 버튼 경로 프롬프트에 넣는 강의 소개의 최대 글자 수(입력 토큰 상한)
 * @param rateLimit        관심사를 넣은 요청(임베딩 비용)의 회원별 호출 제한
 * @param indexCheckBatch  정합 점검이 한 번에 읽는 강의 수
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.recommend.lecture")
public record ChatbotLectureRecommendProperties(
        @DefaultValue("5") @Positive int resultSize,
        @DefaultValue("2") @Positive int maxPerSeller,
        @DefaultValue("50") @Positive int signalLimit,
        @DefaultValue @Valid Weights weights,
        @DefaultValue("0.3") @DecimalMin("0.0") @DecimalMax("1.0") double interestWeight,
        @DefaultValue("0.05") @DecimalMin("0.0") @DecimalMax("1.0") double popularityWeight,
        @DefaultValue("50") @Positive int knnK,
        @DefaultValue("100") @Positive int numCandidates,
        @DefaultValue("50") @Positive int popularPoolSize,
        @DefaultValue("10s") @NotNull Duration explainTimeout,
        @DefaultValue("80") @Positive int reasonMaxLength,
        @DefaultValue("300") @Positive int explainDescriptionMaxLength,
        @DefaultValue @Valid RateLimit rateLimit,
        @DefaultValue("500") @Positive int indexCheckBatch) {

    /**
     * 신호 가중치. 강의 가중 = 기본(구매 또는 장바구니) + 그 강의 리뷰 별점 보정, 0 아래로 내리지 않는다.
     *
     * @param owned   구매({@code OWNED})
     * @param cart    장바구니(삭제 안 됨)
     * @param rating1 리뷰 1점 보정
     * @param rating2 리뷰 2점 보정
     * @param rating3 리뷰 3점 보정
     * @param rating4 리뷰 4점 보정
     * @param rating5 리뷰 5점 보정
     */
    public record Weights(
            @DefaultValue("1.0") @DecimalMin("0.0") double owned,
            @DefaultValue("0.6") @DecimalMin("0.0") double cart,
            @DefaultValue("-0.5") double rating1,
            @DefaultValue("-0.3") double rating2,
            @DefaultValue("0") double rating3,
            @DefaultValue("0.3") double rating4,
            @DefaultValue("0.5") double rating5) {

        /** 별점(1~5)의 보정값. 범위 밖은 0 */
        public double rating(int stars) {
            return switch (stars) {
                case 1 -> rating1;
                case 2 -> rating2;
                case 3 -> rating3;
                case 4 -> rating4;
                case 5 -> rating5;
                default -> 0;
            };
        }
    }

    /**
     * @param limit   한 주기에 회원 한 명이 관심사를 넣어 부를 수 있는 횟수
     * @param period  주기
     * @param maxKeys 메모리에 둘 회원 수 상한
     */
    public record RateLimit(
            @DefaultValue("10") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
