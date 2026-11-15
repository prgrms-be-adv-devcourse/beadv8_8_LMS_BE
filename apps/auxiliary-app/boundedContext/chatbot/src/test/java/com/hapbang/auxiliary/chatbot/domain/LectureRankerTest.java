package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.LectureRanker.Candidate;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker.Popular;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker.Ranked;

/** 추천 후보 순서·고르기 규칙 (#718) */
class LectureRankerTest {

    @Test
    @DisplayName("최종 = (1−β)·유사도 + β·인기, 인기는 후보 안 min-max(0~1). 인기가 유사도 차이를 뒤집을 수 있다")
    void mixesNormalizedPopularity() {
        List<Candidate> candidates = List.of(
                new Candidate(1, 10, 0.80, 0),
                new Candidate(2, 20, 0.78, 100),
                new Candidate(3, 30, 0.50, 50));

        List<Ranked> ranked = LectureRanker.rank(candidates, List.of(), 0.1, 5, 2);

        assertThat(ranked).extracting(Ranked::lectureId).containsExactly(2L, 1L, 3L);
        assertThat(ranked.get(0).score()).isCloseTo(0.9 * 0.78 + 0.1 * 1.0, within(1e-9));
        assertThat(ranked.get(1).score()).isCloseTo(0.9 * 0.80, within(1e-9));
        assertThat(ranked.get(2).score()).isCloseTo(0.9 * 0.50 + 0.1 * 0.5, within(1e-9));
    }

    @Test
    @DisplayName("인기가 모두 같으면 인기는 0이라 유사도 순서 그대로, 점수가 같으면 강의 ID 내림차순")
    void tiesByLectureIdDescending() {
        List<Candidate> candidates = List.of(
                new Candidate(4, 10, 0.7, 3),
                new Candidate(9, 20, 0.7, 3),
                new Candidate(6, 30, 0.9, 3));

        List<Ranked> ranked = LectureRanker.rank(candidates, List.of(), 0.05, 5, 2);

        assertThat(ranked).extracting(Ranked::lectureId).containsExactly(6L, 9L, 4L);
        assertThat(ranked.get(0).score()).isCloseTo(0.95 * 0.9, within(1e-9));
    }

    @Test
    @DisplayName("판매자당 2개, 모두 5개까지. 같은 판매자의 세 번째 강의는 건너뛰고 다음 판매자로")
    void capsPerSellerAndSize() {
        List<Candidate> candidates = List.of(
                new Candidate(1, 10, 0.99, 0),
                new Candidate(2, 10, 0.98, 0),
                new Candidate(3, 10, 0.97, 0),
                new Candidate(4, 20, 0.96, 0),
                new Candidate(5, 20, 0.95, 0),
                new Candidate(6, 30, 0.94, 0),
                new Candidate(7, 40, 0.93, 0));

        List<Ranked> ranked = LectureRanker.rank(candidates, List.of(), 0.05, 5, 2);

        assertThat(ranked).extracting(Ranked::lectureId).containsExactly(1L, 2L, 4L, 5L, 6L);
        assertThat(ranked).noneMatch(Ranked::popular);
    }

    @Test
    @DisplayName("모자라면 인기순 목록으로 채운다. 이미 고른 강의는 건너뛰고 판매자 상한도 함께 센다. 채운 강의는 점수 0·popular")
    void fillsWithPopular() {
        List<Candidate> candidates = List.of(
                new Candidate(1, 10, 0.9, 0),
                new Candidate(2, 10, 0.8, 0));
        List<Popular> popular = List.of(
                new Popular(1, 10),
                new Popular(3, 10),
                new Popular(4, 20),
                new Popular(5, 30),
                new Popular(6, 40),
                new Popular(7, 50));

        List<Ranked> ranked = LectureRanker.rank(candidates, popular, 0.05, 5, 2);

        assertThat(ranked).extracting(Ranked::lectureId).containsExactly(1L, 2L, 4L, 5L, 6L);
        assertThat(ranked.subList(2, 5)).allSatisfy(item -> {
            assertThat(item.popular()).isTrue();
            assertThat(item.score()).isZero();
        });
    }
}
