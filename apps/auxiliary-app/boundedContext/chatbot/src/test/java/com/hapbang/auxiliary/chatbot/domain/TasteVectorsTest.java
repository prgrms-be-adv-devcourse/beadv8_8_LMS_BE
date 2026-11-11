package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 취향 벡터 계산 (#718) */
class TasteVectorsTest {

    @Test
    @DisplayName("가중 평균: 가중 1.5와 0.5면 3:1로 섞인다. 가중 0 이하·벡터 없는 강의는 빠진다")
    void weightedAverage() {
        Map<Long, float[]> vectors = Map.of(1L, new float[] {1, 0}, 2L, new float[] {0, 1}, 3L, new float[] {5, 5});
        Map<Long, Double> weights = Map.of(1L, 1.5, 2L, 0.5, 3L, 0.0, 4L, 1.0);

        float[] average = TasteVectors.weightedAverage(vectors, weights);

        assertThat(average).containsExactly(0.75f, 0.25f);
    }

    @Test
    @DisplayName("쓸 가중이 없으면 null(신호 없음과 같다)")
    void noWeightIsNull() {
        assertThat(TasteVectors.weightedAverage(Map.of(1L, new float[] {1, 0}), Map.of(1L, 0.0))).isNull();
        assertThat(TasteVectors.weightedAverage(Map.<Long, float[]>of(), Map.of(1L, 1.0))).isNull();
    }

    @Test
    @DisplayName("혼합: 크기가 달라도 정규화한 뒤 (1−α)·신호 + α·관심사")
    void mixNormalizes() {
        float[] mixed = TasteVectors.mix(new float[] {10, 0}, new float[] {0, 2}, 0.3);

        assertThat(mixed[0]).isCloseTo(0.7f, within(1e-6f));
        assertThat(mixed[1]).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    @DisplayName("코사인: 같은 방향 1, 직교 0, 0 벡터 0")
    void cosine() {
        assertThat(TasteVectors.cosine(new float[] {2, 0}, new float[] {5, 0})).isCloseTo(1.0, within(1e-9));
        assertThat(TasteVectors.cosine(new float[] {1, 0}, new float[] {0, 3})).isCloseTo(0.0, within(1e-9));
        assertThat(TasteVectors.cosine(new float[] {0, 0}, new float[] {0, 3})).isZero();
    }
}
