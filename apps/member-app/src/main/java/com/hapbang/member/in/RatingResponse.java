package com.hapbang.member.in;

import com.hapbang.member.domain.RatingSummary;

/** 평점 (#555). average는 소수 첫째 자리까지, 리뷰가 없으면 average는 null, count는 0 */
public record RatingResponse(Double average, long count) {

    public static RatingResponse from(RatingSummary summary) {
        return new RatingResponse(summary.average(), summary.count());
    }
}
