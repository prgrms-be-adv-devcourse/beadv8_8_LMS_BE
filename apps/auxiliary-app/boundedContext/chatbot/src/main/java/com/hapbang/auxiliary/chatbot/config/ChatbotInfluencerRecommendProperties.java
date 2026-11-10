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
 * 인플루언서 추천 설정 {@code hapbang.chatbot.recommend.influencer} (#719). 정책 숫자(가중치·상한)는 코드에 두지 않고 여기서 정한다.
 *
 * @param size              돌려줄 인플루언서 수. 후보가 모자라면 인기순으로 채워 이 수를 맞춘다
 * @param k                 kNN으로 받을 후보 수
 * @param numCandidates     kNN이 샤드마다 살펴볼 후보 수(k 이상)
 * @param partnerWeight     내 프로필 벡터와 함께 출연을 마친 상대들의 평균 벡터를 섞는 비율(상대 쪽 비중)
 * @param popularityWeight  최종 점수 = (1 − β)·유사도 + β·정규화 인기의 β
 * @param popularity        인기 점수
 * @param rateLimit         LLM 설명 버튼 경로({@code /explained})의 회원별 호출 제한
 * @param explainTimeout    LLM 설명 한 번의 시간 상한. 넘으면 이유 코드 문장으로 답한다
 * @param checkCron         색인 정합 점검 주기(회원 싱크 :15 뒤). 첫 배포 뒤 첫 점검이 처음 전체 색인 요청을 적는다
 * @param checkPageSize     정합 점검이 한 번에 읽는 회원 수
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.recommend.influencer")
public record ChatbotInfluencerRecommendProperties(
        @DefaultValue("5") @Positive int size,
        @DefaultValue("50") @Positive int k,
        @DefaultValue("200") @Positive int numCandidates,
        @DefaultValue("0.5") @DecimalMin("0") @DecimalMax("1") double partnerWeight,
        @DefaultValue("0.2") @DecimalMin("0") @DecimalMax("1") double popularityWeight,
        @DefaultValue @Valid Popularity popularity,
        @DefaultValue @Valid RateLimit rateLimit,
        @DefaultValue("15s") @NotNull Duration explainTimeout,
        @DefaultValue("0 45 * * * *") @NotBlank String checkCron,
        @DefaultValue("500") @Positive int checkPageSize) {

    public ChatbotInfluencerRecommendProperties {
        if (numCandidates < k) {
            throw new IllegalArgumentException("hapbang.chatbot.recommend.influencer.num-candidates는 k 이상이어야 합니다.");
        }
    }

    /**
     * 인기 점수: 원점수 = completedWeight·ln(1 + 완료 출연 수) + ratingWeight·베이지안 평균 별점.
     *
     * @param completedWeight 완료 출연 수 가중치
     * @param ratingWeight    베이지안 평균 별점 가중치
     * @param priorReviews    베이지안 평균의 C(리뷰가 이만큼 쌓이기 전에는 전체 평균 쪽으로 당긴다)
     */
    public record Popularity(
            @DefaultValue("1.0") @DecimalMin("0") double completedWeight,
            @DefaultValue("1.0") @DecimalMin("0") double ratingWeight,
            @DefaultValue("5") @DecimalMin("0") double priorReviews) {
    }

    /** 회원별 호출 제한(키는 회원 ID) */
    public record RateLimit(
            @DefaultValue("10") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
