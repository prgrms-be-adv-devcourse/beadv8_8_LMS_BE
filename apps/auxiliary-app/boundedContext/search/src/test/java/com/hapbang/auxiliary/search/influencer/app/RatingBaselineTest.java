package com.hapbang.auxiliary.search.influencer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.influencer.app.ContentCategoryNamesTest.MutableClock;
import com.hapbang.auxiliary.search.influencer.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;

/** 평점 전체 평균 C 캐시: 주기, 실패하면 1분 동안 다시 묻지 않음(#771 코드 리뷰) */
class RatingBaselineTest {

    private final InfluencerSearcher searcher = mock(InfluencerSearcher.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));

    private final RatingBaseline baseline = new RatingBaseline(searcher,
            new SearchRankingProperties(null, null, null, Duration.ofMinutes(10)), clock);

    @Test
    @DisplayName("주기 안에는 다시 묻지 않고, 지나면 다시 구한다. 리뷰가 없으면 0이다")
    void refreshesAfterTtl() {
        when(searcher.ratingBaseline()).thenReturn(4.3, (Double) null);

        assertThat(baseline.get()).isEqualTo(4.3);
        clock.advance(Duration.ofMinutes(9));
        assertThat(baseline.get()).isEqualTo(4.3);
        clock.advance(Duration.ofMinutes(1));
        assertThat(baseline.get()).isZero();
        verify(searcher, times(2)).ratingBaseline();
    }

    @Test
    @DisplayName("다시 구하다 실패하면 가진 값을 쓰고 1분 동안 ES에 묻지 않는다")
    void keepsValueOnFailure() {
        when(searcher.ratingBaseline()).thenReturn(4.3).thenThrow(new IllegalStateException("ES 장애")).thenReturn(4.5);
        baseline.get();
        clock.advance(Duration.ofMinutes(10));

        assertThat(baseline.get()).isEqualTo(4.3);
        assertThat(baseline.get()).isEqualTo(4.3);
        verify(searcher, times(2)).ratingBaseline();

        clock.advance(Duration.ofMinutes(1));
        assertThat(baseline.get()).isEqualTo(4.5);
    }

    @Test
    @DisplayName("한 번도 구하지 못했으면 던지고, 1분 안에는 ES에 묻지 않고 바로 던진다")
    void throwsWhenNeverKnown() {
        when(searcher.ratingBaseline()).thenThrow(new IllegalStateException("ES 장애"));

        assertThatThrownBy(baseline::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(baseline::get).isInstanceOf(IllegalStateException.class);
        verify(searcher, times(1)).ratingBaseline();
    }
}
