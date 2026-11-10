package com.hapbang.auxiliary.search.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인플루언서 검색 순위 계수({@code hapbang.search.ranking.*}, #771). 근거는 golden query 테스트와 PR 본문에 있다.
 *
 * @param followerBoost      팔로워 가산 계수 a. 검색어가 있을 때 점수 × (1 + a·log10(1 + 팔로워)). 텍스트 관련도가 먼저고 비슷할 때만 팔로워가
 *                           가르도록 작게 둔다. golden query 통과 구간 0.02~0.1의 가운데(0은 팔로워가 순서를 못 가르고, 0.5 이상은 팔로워가
 *                           텍스트 일치를 뒤집음, {@code GoldenQueryTest#coefficientGrid})
 * @param verifiedBoost      인증된 인플루언서 가산(곱). 소폭(1.1이면 a=0.1에서 G01이 깨짐)
 * @param ratingPriorReviews 평점순 베이지안 평균의 기준 리뷰 수 m. (v×R + m×C)/(v+m). 5면 리뷰 1건 5.0이 리뷰가 많은 4.7·4.8 위로
 *                           오지 못한다(G20). 실제 리뷰 분포가 쌓이면 다시 본다
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
