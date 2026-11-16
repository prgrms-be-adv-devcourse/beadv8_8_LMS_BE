package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalGrid.Combo;

class RecommendEvalGridTest {

    // 테스트 yaml처럼 α·β만 적고 나머지는 설정 클래스 기본값
    private final RecommendEvalGrid grid = new RecommendEvalGrid(List.of(new MapConfigurationPropertySource(Map.of(
            "hapbang.chatbot.recommend.lecture.interest-weight", "0.3",
            "hapbang.chatbot.recommend.lecture.popularity-weight", "0.05"))));

    @Test
    void noGridIsOneBaselineForBoth() {
        List<Combo> combos = RecommendEvalGrid.combos(null, null);

        assertThat(combos).singleElement().satisfies(combo -> {
            assertThat(combo.label()).isEqualTo("기준선");
            assertThat(combo.lecture()).isTrue();
            assertThat(combo.influencer()).isTrue();
        });
        ChatbotLectureRecommendProperties lecture = grid.lecture(combos.getFirst(), 5);
        assertThat(lecture.popularityWeight()).isEqualTo(0.05);
        assertThat(lecture.weights().cart()).isEqualTo(0.6);
        assertThat(lecture.resultSize()).isEqualTo(5);
    }

    @Test
    void twoKeysMakeEveryCombinationOverBase() {
        List<Combo> combos = RecommendEvalGrid.combos(" lecture.popularity-weight = 0, 0.1 ; lecture.weights.cart=0.4,0.8",
                "lecture.interest-weight=0.5");

        assertThat(combos).extracting(Combo::label).containsExactly(
                "lecture.popularity-weight=0, lecture.weights.cart=0.4",
                "lecture.popularity-weight=0, lecture.weights.cart=0.8",
                "lecture.popularity-weight=0.1, lecture.weights.cart=0.4",
                "lecture.popularity-weight=0.1, lecture.weights.cart=0.8");
        assertThat(combos).allSatisfy(combo -> assertThat(combo.influencer()).as("강의 키만 바꿈").isFalse());
        ChatbotLectureRecommendProperties last = grid.lecture(combos.getLast(), 20);
        assertThat(last.popularityWeight()).isEqualTo(0.1);
        assertThat(last.weights().cart()).isEqualTo(0.8);
        assertThat(last.interestWeight()).as("고정값").isEqualTo(0.5);
        assertThat(last.resultSize()).isEqualTo(20);
    }

    @Test
    void influencerNestedKey() {
        Combo combo = RecommendEvalGrid.combos("influencer.popularity.prior-reviews=10", null).getFirst();

        ChatbotInfluencerRecommendProperties influencer = grid.influencer(combo, 20);

        assertThat(combo.lecture()).isFalse();
        assertThat(influencer.popularity().priorReviews()).isEqualTo(10);
        assertThat(influencer.popularityWeight()).isEqualTo(0.2);
        assertThat(influencer.size()).isEqualTo(20);
    }

    @Test
    void rejectsUnknownKeyTooManyKeysAndBadFormat() {
        assertThatThrownBy(() -> RecommendEvalGrid.combos("lecture.popularity-wieght=0.1", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모르는 키");
        assertThatThrownBy(() -> RecommendEvalGrid.combos("lecture.result-size=20", null))
                .as("결과 수는 평가가 정한다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendEvalGrid.combos(
                "lecture.popularity-weight=0;lecture.interest-weight=0;lecture.weights.cart=0", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1~2개");
        assertThatThrownBy(() -> RecommendEvalGrid.combos("lecture.popularity-weight", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendEvalGrid.combos(null, "lecture.popularity-weight=0.1,0.2"))
                .as("고정값은 하나").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeShowsBeforeAndAfter() {
        List<Combo> combos = RecommendEvalGrid.combos("lecture.popularity-weight=0.1;influencer.popularity.prior-reviews=10",
                "lecture.popularity-weight=0.2");

        assertThat(grid.change(combos.getFirst()))
                .as("전은 고정값까지 적용한 값")
                .isEqualTo("lecture.popularity-weight: 0.2 → 0.1, influencer.popularity.prior-reviews: 5.0 → 10");
        assertThat(grid.change(RecommendEvalGrid.combos(null, null).getFirst())).isEqualTo("기준선");
        assertThat(grid.change(RecommendEvalGrid.combos(null, "lecture.popularity-weight=0.2").getFirst())).isEqualTo("고정값");
    }

    @Test
    void rejectsMoreThanFiveValues() {
        assertThat(RecommendEvalGrid.combos("lecture.popularity-weight=0,0.05,0.1,0.2,0.3", null)).hasSize(5);
        assertThatThrownBy(() -> RecommendEvalGrid.combos("lecture.popularity-weight=0,0.05,0.1,0.2,0.3,0.4", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5개까지");
    }

    @Test
    void rejectsOutOfRangeValue() {
        Combo combo = RecommendEvalGrid.combos("lecture.popularity-weight=1.5", null).getFirst();

        assertThatThrownBy(() -> grid.lecture(combo, 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("popularityWeight");
    }
}
