package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Pick;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Tally;

class RecommendMetricsTest {

    // ID 1..n, 앞 personalizedCount개만 맞춤 후보
    private static List<Pick> picks(int n, int personalizedCount) {
        List<Pick> picks = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            picks.add(new Pick(i, i <= personalizedCount));
        }
        return picks;
    }

    @Test
    void ndcgByRank() {
        List<Pick> top = picks(5, 5);

        assertThat(RecommendMetrics.ndcgAt5(1, top)).isEqualTo(1.0);
        assertThat(RecommendMetrics.ndcgAt5(2, top)).isCloseTo(1 / (Math.log(3) / Math.log(2)), within(1e-12));
        assertThat(RecommendMetrics.ndcgAt5(5, top)).isCloseTo(1 / (Math.log(6) / Math.log(2)), within(1e-12));
        assertThat(RecommendMetrics.ndcgAt5(6, picks(6, 6))).as("6위는 5개 밖").isZero();
        assertThat(RecommendMetrics.ndcgAt5(99, top)).isZero();
    }

    @Test
    void hitCountsPopularFillButRecallDoesNot() {
        Tally tally = new Tally();
        // 정답 4: 5개 안이지만 인기순으로 채운 항목(맞춤 후보 3개)
        tally.add(4, picks(5, 3), picks(20, 3));

        assertThat(tally.hitAt5()).as("사용자가 보는 5개에 있다").isEqualTo(1.0);
        assertThat(tally.recallAt20()).as("맞춤 후보 풀에는 없다").isZero();
    }

    @Test
    void recallLooksAtTwentyPersonalized() {
        Tally tally = new Tally();
        tally.add(20, picks(5, 5), picks(25, 25));
        tally.add(21, picks(5, 5), picks(25, 25));

        assertThat(tally.hitAt5()).isZero();
        assertThat(tally.recallAt20()).as("20위는 들고 21위는 빠진다").isEqualTo(0.5);
    }

    @Test
    void hitLooksAtFirstFiveOnly() {
        Tally tally = new Tally();
        tally.add(6, picks(6, 6), picks(20, 20));

        assertThat(tally.hitAt5()).isZero();
        assertThat(tally.distinctTop5()).isEqualTo(5);
    }

    @Test
    void averagesAndDistinctTop5() {
        Tally tally = new Tally();
        tally.add(1, picks(5, 5), picks(20, 20));
        tally.add(9, List.of(new Pick(1, true), new Pick(6, true), new Pick(7, true), new Pick(8, true), new Pick(9, true)),
                picks(20, 20));
        tally.add(50, picks(5, 5), picks(20, 20));

        assertThat(tally.members()).isEqualTo(3);
        assertThat(tally.hitAt5()).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(tally.ndcgAt5()).isCloseTo((1 + 1 / (Math.log(6) / Math.log(2))) / 3, within(1e-12));
        assertThat(tally.recallAt20()).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(tally.distinctTop5()).as("1~9").isEqualTo(9);
    }

    @Test
    void emptyTallyIsZero() {
        Tally tally = new Tally();

        assertThat(tally.hitAt5()).isZero();
        assertThat(tally.ndcgAt5()).isZero();
        assertThat(tally.recallAt20()).isZero();
    }
}
