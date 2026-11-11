package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RatingSummaryTest {

    @ParameterizedTest
    @CsvSource({
            "9, 2, 4.5",   // 5·4
            "13, 3, 4.3",  // 5·4·4 = 4.333…
            "17, 4, 4.3",  // 5·4·4·4 = 4.25 → 반올림
            "11, 3, 3.7",  // 5·3·3 = 3.666…
            "5, 1, 5.0"})
    @DisplayName("평균은 합계 / 개수를 소수 첫째 자리에서 반올림한다")
    void of(long sum, long count, double average) {
        RatingSummary summary = RatingSummary.of(sum, count);

        assertThat(summary.average()).isEqualTo(average);
        assertThat(summary.count()).isEqualTo(count);
    }

    @Test
    @DisplayName("리뷰가 없으면 평균은 null, 개수는 0이다")
    void empty() {
        assertThat(RatingSummary.of(0, 0)).isEqualTo(new RatingSummary(null, 0));
    }
}
