package com.hapbang.member.domain;

/**
 * 회원 출연 평점 (#555). 받은 회원이 그 출연에서 맡은 역할로 나눈 평점과 전체 평점
 *
 * @param total   받은 출연 리뷰 전체
 * @param asHost  호스트로 받은 리뷰 (게스트가 쓴 것)
 * @param asGuest 게스트로 받은 리뷰 (호스트가 쓴 것)
 */
public record AppearanceRatingSummary(RatingSummary total, RatingSummary asHost, RatingSummary asGuest) {

    /** 받은 리뷰가 없을 때 */
    public static final AppearanceRatingSummary EMPTY =
            new AppearanceRatingSummary(RatingSummary.EMPTY, RatingSummary.EMPTY, RatingSummary.EMPTY);
}
