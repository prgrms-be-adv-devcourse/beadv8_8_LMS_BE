package com.hapbang.auxiliary.search.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의 검색 순위 계수({@code hapbang.search.lecture-ranking.*}, #883 L11). 근거는 강의 golden query 테스트와 PR 본문에 있다.
 * 필드 가중치(제목×3·콘텐츠 제목×2·판매자 닉네임×2·소개×1)는 인플루언서와 같이 쿼리 상수로 두고 golden 테스트가 고정한다.
 *
 * @param ratingBoost        평점 가산 계수 b. 검색어가 있을 때 점수 × (1 + b × 베이지안 평균 / 5). 글 관련도가 먼저고 비슷할 때만 평점이
 *                           가르도록 작게 둔다. golden query 통과 구간 0.05~1.0의 아래쪽(0은 글이 같은 강의를 평점이 못 가르고, 5는 글이 더
 *                           맞는 강의를 평점이 뒤집음, {@code LectureGoldenQueryTest}). 0.1이면 평점이 주는 이득은 보통 범위(보정 평균
 *                           3.5~4.8)에서 약 2.4%, 극단(5 대 1)에서 약 8%, 식의 상한은 10%라 비슷한 글끼리만 가른다(#883).
 *                           실제 리뷰 분포가 쌓이면 다시 본다(바꿔도 재색인은 필요 없다)
 * @param ratingPriorReviews 베이지안 평균 (n×R + m×C)/(n+m)의 기준 리뷰 수 m. 리뷰 1건 5.0이 리뷰가 많은 4.7·4.8 위로 오지 못하게 한다
 *                           (인플루언서와 같은 값)
 * @param ratingBaselineTtl  전체 평균 C를 pod가 다시 구하는 주기
 */
@ConfigurationProperties("hapbang.search.lecture-ranking")
public record LectureRankingProperties(Double ratingBoost, Integer ratingPriorReviews, Duration ratingBaselineTtl) {

    public LectureRankingProperties {
        ratingBoost = ratingBoost == null ? 0.1 : ratingBoost;
        ratingPriorReviews = ratingPriorReviews == null ? 5 : ratingPriorReviews;
        ratingBaselineTtl = ratingBaselineTtl == null ? Duration.ofMinutes(10) : ratingBaselineTtl;
    }
}
