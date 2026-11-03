package com.hapbang.member.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 평점 요약 (#555). 별점은 1~5 정수지만 평균은 소수라 소수 첫째 자리에서 반올림한다(4.25 → 4.3).
 * 화면마다 반올림이 달라지지 않게 서버가 정한다
 *
 * @param average 평균. 리뷰가 없으면 null
 * @param count   리뷰 수
 */
public record RatingSummary(Double average, long count) {

    public static final RatingSummary EMPTY = new RatingSummary(null, 0);

    /** 별점 합계와 개수로 만든다 */
    public static RatingSummary of(long sum, long count) {
        if (count == 0) {
            return EMPTY;
        }
        double average = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP)
                .doubleValue();
        return new RatingSummary(average, count);
    }
}
