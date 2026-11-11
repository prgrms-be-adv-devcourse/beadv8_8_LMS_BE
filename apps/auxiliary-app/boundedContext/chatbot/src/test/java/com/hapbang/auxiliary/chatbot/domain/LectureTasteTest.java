package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Signal;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Source;
import com.hapbang.auxiliary.chatbot.domain.LectureTaste.Reasoned;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;

/** 한 요청의 취향: 질의 벡터와 고른 강의의 이유 (#718). 벡터는 축으로 정해 거리를 통제한다(8차원) */
class LectureTasteTest {

    private static final float[] X0 = FakeEmbeddingModel.axis(0);
    private static final float[] X1 = FakeEmbeddingModel.axis(1);
    private static final float[] X2 = FakeEmbeddingModel.axis(2);

    @Test
    @DisplayName("질의 벡터: 신호만 있으면 신호 가중 평균, 관심사만 있으면 관심사, 둘 다 있으면 (1−α)·신호 + α·관심사")
    void query() {
        List<Signal> signals = List.of(new Signal(1, Source.PURCHASED, 1.0), new Signal(2, Source.CART, 1.0));
        Map<Long, float[]> vectors = Map.of(1L, X0, 2L, X1);

        float[] signalOnly = LectureTaste.of(signals, vectors, null).query(0.3);
        float[] interestOnly = LectureTaste.of(List.of(), Map.of(), X2).query(0.3);
        float[] mixed = LectureTaste.of(List.of(new Signal(1, Source.PURCHASED, 1.0)), vectors, X2).query(0.3);

        assertThat(signalOnly[0]).isEqualTo(signalOnly[1]).isPositive();
        assertThat(interestOnly).containsExactly(X2);
        assertThat(mixed[0]).isCloseTo(0.7f, within(1e-6f));
        assertThat(mixed[2]).isCloseTo(0.3f, within(1e-6f));
    }

    @Test
    @DisplayName("신호 강의가 하나도 색인되지 않았고 관심사도 없으면 질의 벡터가 없다")
    void noQueryWithoutIndexedSignal() {
        LectureTaste taste = LectureTaste.of(List.of(new Signal(1, Source.PURCHASED, 1.0)), Map.of(), null);

        assertThat(taste.query(0.3)).isNull();
    }

    @Test
    @DisplayName("이유: 가장 가까운 출처 중심. 기준 강의는 그 출처의 신호 강의 중 가장 가까운 강의")
    void closestSourceAndBasis() {
        List<Signal> signals = List.of(new Signal(1, Source.PURCHASED, 1.0), new Signal(2, Source.CART, 0.6),
                new Signal(3, Source.CART, 0.6));
        LectureTaste taste = LectureTaste.of(signals,
                Map.of(1L, X0, 2L, X1, 3L, FakeEmbeddingModel.withCosine(1, 2, 0.5)), null);

        assertThat(taste.reason(FakeEmbeddingModel.withCosine(0, 2, 0.9)))
                .isEqualTo(new Reasoned(LectureRecommendReason.SIMILAR_TO_PURCHASED, 1L));
        assertThat(taste.reason(FakeEmbeddingModel.withCosine(1, 3, 0.9)))
                .isEqualTo(new Reasoned(LectureRecommendReason.SIMILAR_TO_CART, 2L));
    }

    @Test
    @DisplayName("이유: 관심사 벡터가 어느 출처 중심보다 가까우면 MATCHES_INTEREST(기준 강의 없음)")
    void interestWins() {
        LectureTaste taste = LectureTaste.of(List.of(new Signal(1, Source.PURCHASED, 1.0)), Map.of(1L, X0), X2);

        assertThat(taste.reason(FakeEmbeddingModel.withCosine(2, 0, 0.9))).isEqualTo(Reasoned.INTEREST);
        assertThat(taste.reason(FakeEmbeddingModel.withCosine(0, 2, 0.9)).reason())
                .isEqualTo(LectureRecommendReason.SIMILAR_TO_PURCHASED);
    }

    @Test
    @DisplayName("고른 강의 벡터가 없으면 색인된 신호의 가중 합이 큰 출처로 정하고 기준 강의는 두지 않는다. 색인 전 신호는 세지 않는다")
    void missingVectorUsesHeaviestIndexedSource() {
        // 장바구니 0.6 + 0.6 = 1.2 > 구매 1.0
        List<Signal> signals = List.of(new Signal(1, Source.PURCHASED, 1.0), new Signal(2, Source.CART, 0.6),
                new Signal(3, Source.CART, 0.6));
        LectureTaste allIndexed = LectureTaste.of(signals, Map.of(1L, X0, 2L, X1, 3L, X2), null);
        // 3번이 색인 전이면 장바구니는 0.6뿐이다
        LectureTaste oneCartUnindexed = LectureTaste.of(signals, Map.of(1L, X0, 2L, X1), null);

        assertThat(allIndexed.reason(null)).isEqualTo(new Reasoned(LectureRecommendReason.SIMILAR_TO_CART, null));
        assertThat(oneCartUnindexed.reason(null)).isEqualTo(new Reasoned(LectureRecommendReason.SIMILAR_TO_PURCHASED, null));
    }

    @Test
    @DisplayName("고른 강의 벡터가 없고 신호 중심도 없으면(관심사만) MATCHES_INTEREST")
    void missingVectorWithInterestOnly() {
        LectureTaste taste = LectureTaste.of(List.of(), Map.of(), X2);

        assertThat(taste.reason(null)).isEqualTo(Reasoned.INTEREST);
    }

    @Test
    @DisplayName("출처마다 이유 코드가 있고, 같은 강의의 출처가 둘이면 구매가 강하다")
    void sourceReasonAndStrength() {
        assertThat(Source.PURCHASED.reason()).isEqualTo(LectureRecommendReason.SIMILAR_TO_PURCHASED);
        assertThat(Source.CART.reason()).isEqualTo(LectureRecommendReason.SIMILAR_TO_CART);
        assertThat(Source.stronger(Source.CART, Source.PURCHASED)).isEqualTo(Source.PURCHASED);
        assertThat(Source.stronger(Source.PURCHASED, Source.CART)).isEqualTo(Source.PURCHASED);
        assertThat(Source.stronger(Source.CART, Source.CART)).isEqualTo(Source.CART);
    }
}
