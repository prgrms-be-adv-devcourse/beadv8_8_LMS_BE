package com.hapbang.member.in;

import com.hapbang.member.domain.AppearanceRatingSummary;

/**
 * 회원 출연 평점 (#555). 프로필 상세에서 전체와 받은 역할별 평점을 함께 준다
 *
 * @param total   받은 출연 리뷰 전체
 * @param asHost  호스트로 받은 리뷰 (게스트가 쓴 것)
 * @param asGuest 게스트로 받은 리뷰 (호스트가 쓴 것)
 */
public record AppearanceRatingResponse(RatingResponse total, RatingResponse asHost, RatingResponse asGuest) {

    public static AppearanceRatingResponse from(AppearanceRatingSummary summary) {
        return new AppearanceRatingResponse(RatingResponse.from(summary.total()),
                RatingResponse.from(summary.asHost()), RatingResponse.from(summary.asGuest()));
    }
}
