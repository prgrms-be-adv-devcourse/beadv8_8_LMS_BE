package com.hapbang.auxiliary.search.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인플루언서 검색 순위 계수({@code hapbang.search.ranking.*}, #771). 근거는 golden query 테스트와 PR 본문에 있다.
 *
 * @param followerBoost      팔로워 가산 계수 a. 검색어가 있을 때 점수 × (1 + a·log10(1 + 팔로워)). 텍스트 관련도가 먼저고 비슷할 때만 팔로워가
 *                           가르도록 작게 둔다(시험: 0.05 미만은 팔로워가 순서를 못 가르고, 1.0 이상은 엉뚱한 일치가 진짜 일치를 밀어냄)
 * @param verifiedBoost      인증된 인플루언서 가산(곱). 소폭
 * @param ratingPriorReviews 평점순 베이지안 평균의 기준 리뷰 수 m. (v×R + m×C)/(v+m)
 * @param ratingBaselineTtl  베이지안 평균의 전체 평균 C를 pod가 다시 구하는 주기
 */
@ConfigurationProperties("hapbang.search.ranking")
public record SearchRankingProperties(Double followerBoost, Double verifiedBoost, Integer ratingPriorReviews,
                                      Duration ratingBaselineTtl) {

    public SearchRankingProperties {
        followerBoost = followerBoost == null ? 0.05 : followerBoost;
        verifiedBoost = verifiedBoost == null ? 1.05 : verifiedBoost;
        ratingPriorReviews = ratingPriorReviews == null ? 5 : ratingPriorReviews;
        ratingBaselineTtl = ratingBaselineTtl == null ? Duration.ofMinutes(10) : ratingBaselineTtl;
    }
}
