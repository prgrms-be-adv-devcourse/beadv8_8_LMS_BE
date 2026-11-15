package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Activity;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Signal;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Source;

/** 강의 신호 가중 (#718) */
class LectureSignalsTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final LectureSignals.Weights WEIGHTS = new LectureSignals.Weights(1.0, 0.6, stars -> switch (stars) {
        case 1 -> -0.5;
        case 2 -> -0.3;
        case 4 -> 0.3;
        case 5 -> 0.5;
        default -> 0;
    });

    @Test
    @DisplayName("구매 1.0·장바구니 0.6에 그 강의 리뷰 별점 보정을 더한다. 0 아래로 내리지 않고, 0이면 신호에서 뺀다")
    void weighsWithReviewAndFloor() {
        List<Activity> activities = List.of(
                new Activity(1, Source.PURCHASED, T),
                new Activity(2, Source.PURCHASED, T.minusDays(1)),
                new Activity(3, Source.CART, T.minusDays(2)),
                new Activity(4, Source.CART, T.minusDays(3)));
        // 1: 5점 +0.5, 2: 1점 −0.5, 4(장바구니 0.6): 1점 −0.5 → 0.1, 3: 별점 없음
        Map<Long, Integer> ratings = Map.of(1L, 5, 2L, 1, 4L, 1, 99L, 5);

        List<Signal> signals = LectureSignals.weigh(activities, ratings, 50, WEIGHTS);

        assertThat(signals).extracting(Signal::lectureId, Signal::source)
                .containsExactly(tuple(1L, Source.PURCHASED), tuple(2L, Source.PURCHASED), tuple(3L, Source.CART),
                        tuple(4L, Source.CART));
        assertThat(signals).extracting(Signal::weight).containsExactly(1.5, 0.5, 0.6, 0.6 - 0.5);
    }

    @Test
    @DisplayName("가중이 0 이하가 되면 빠진다: 장바구니 0.6 + 1점(−0.5 아래로 낮춘 설정) → 하한 0, 신호 아님. 리뷰만 있는 강의는 신호가 아니다")
    void dropsNonPositive() {
        LectureSignals.Weights harsh = new LectureSignals.Weights(1.0, 0.6, stars -> stars == 1 ? -0.8 : 0);

        List<Signal> signals = LectureSignals.weigh(List.of(new Activity(3, Source.CART, T)), Map.of(3L, 1, 7L, 5), 50,
                harsh);

        assertThat(signals).isEmpty();
    }

    @Test
    @DisplayName("한 강의에 구매와 장바구니가 함께 있으면 구매로 보고, 늦은 시각으로 최근 순 상한을 센다")
    void mergesAndLimitsByRecency() {
        List<Activity> activities = List.of(
                new Activity(1, Source.CART, T.minusDays(5)),
                new Activity(1, Source.PURCHASED, T.minusDays(9)),
                new Activity(2, Source.CART, T.minusDays(1)),
                new Activity(3, Source.PURCHASED, T.minusDays(7)));

        List<Signal> signals = LectureSignals.weigh(activities, Map.of(), 2, WEIGHTS);

        assertThat(signals).extracting(Signal::lectureId, Signal::source, Signal::weight)
                .containsExactly(tuple(2L, Source.CART, 0.6), tuple(1L, Source.PURCHASED, 1.0));
    }
}
