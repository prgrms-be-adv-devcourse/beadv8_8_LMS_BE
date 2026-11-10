package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity.Stats;

/** 인플루언서 인기 점수 (#719) */
class InfluencerPopularityTest {

    private final InfluencerPopularity popularity = new InfluencerPopularity(1.0, 1.0, 5);

    @Test
    @DisplayName("베이지안 평균: 리뷰가 적으면 전체 평균 쪽으로 당긴다. 5점 1개 < 4.6점 20개")
    void bayesian() {
        double mean = 4.0;
        Stats oneFive = new Stats(1, 0, 1, 5);
        Stats manyGood = new Stats(2, 0, 20, 92);

        assertThat(popularity.bayesianRating(oneFive, mean)).isCloseTo((5 * 4.0 + 5) / 6, within(1e-9));
        assertThat(popularity.bayesianRating(manyGood, mean)).isCloseTo((5 * 4.0 + 92) / 25, within(1e-9));
        assertThat(popularity.bayesianRating(oneFive, mean)).isLessThan(popularity.bayesianRating(manyGood, mean));
        // 리뷰가 없으면 전체 평균
        assertThat(popularity.bayesianRating(Stats.empty(3), mean)).isEqualTo(mean);
    }

    @Test
    @DisplayName("원점수 = w1·ln(1 + 완료 수) + w2·보정 평균")
    void raw() {
        Stats stats = new Stats(1, 3, 0, 0);

        assertThat(popularity.raw(stats, 4.0)).isCloseTo(Math.log(4) + 4.0, within(1e-9));
        assertThat(new InfluencerPopularity(2.0, 0.5, 5).raw(stats, 4.0)).isCloseTo(2 * Math.log(4) + 2.0, within(1e-9));
    }

    @Test
    @DisplayName("정규화: 후보 안에서 0~1 min-max. 모두 같거나 하나뿐이면 0")
    void normalize() {
        Map<Long, Double> normalized = InfluencerPopularity.normalize(Map.of(1L, 2.0, 2L, 4.0, 3L, 3.0));

        assertThat(normalized).containsEntry(1L, 0.0).containsEntry(2L, 1.0).containsEntry(3L, 0.5);
        assertThat(InfluencerPopularity.normalize(Map.of(1L, 7.0, 2L, 7.0)).values()).containsOnly(0.0);
        assertThat(InfluencerPopularity.normalize(Map.of(1L, 7.0))).containsEntry(1L, 0.0);
        assertThat(InfluencerPopularity.normalize(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("완료 출연이 많을수록 원점수가 크다(같은 별점)")
    void moreCompletedIsHigher() {
        Map<Long, Double> raw = popularity.rawScores(List.of(new Stats(1, 0, 0, 0), new Stats(2, 5, 0, 0)), 4.0);

        assertThat(raw.get(2L)).isGreaterThan(raw.get(1L));
    }
}
