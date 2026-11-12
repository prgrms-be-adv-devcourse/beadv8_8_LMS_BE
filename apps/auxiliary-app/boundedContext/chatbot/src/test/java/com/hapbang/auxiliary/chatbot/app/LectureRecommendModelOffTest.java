package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.MeterRegistry;

import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 임베딩 모델이 없는 실행(모델을 끈 실행)의 강의 추천 (#718). 가짜 모델을 가져오지 않아 {@link ChatbotEmbedder#available()}이 false다.
 * 관심사를 써도 맞춤으로 찾을 수 없으므로 {@code needsInterest}를 켜지 않는다.
 */
class LectureRecommendModelOffTest extends ChatbotIntegrationTestBase {

    @Autowired
    private LectureRecommendUseCase useCase;

    @Autowired
    private ChatbotEmbedder embedder;

    @Autowired
    private MeterRegistry meterRegistry;

    private RecommendRows data;

    @BeforeEach
    void setUp() {
        data = new RecommendRows(jdbcTemplate);
        data.member(1);
        data.member(2);
        data.member(100);
        data.lecture(10, 100, "강의 10");
        data.lecture(11, 100, "강의 11");
        data.purchase(1, 2, 10, "OWNED", RecommendRows.AT);
    }

    @Test
    @DisplayName("신호가 없는 회원: 인기순이고 needsInterest를 켜지 않는다(관심사를 줘도 맞춤으로 못 찾는다). 원래 인기순이라 fallback으로 세지 않는다")
    void noSignalDoesNotAskInterest() {
        double fallbacks = fallbacks();

        LectureRecommendation result = useCase.recommend(1, null);

        assertThat(embedder.available()).isFalse();
        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(10L, 11L);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reason()).isEqualTo(LectureRecommendReason.POPULAR));
        assertThat(result.needsInterest()).isFalse();
        assertThat(result.basis()).isEqualTo(RecommendBasis.POPULAR);
        assertThat(fallbacks()).isEqualTo(fallbacks);
    }

    @Test
    @DisplayName("신호가 있는 회원·관심사 요청도 인기순·POPULAR다. 모델을 끈 것은 설정이라 다시 시도해도 같고, LLM 실패가 아니라 "
            + "fallback으로 세지 않는다")
    void signalFallsBackToPopular() {
        double fallbacks = fallbacks();

        LectureRecommendation result = useCase.recommend(2, null);
        LectureRecommendation withInterest = useCase.recommend(1, "데이터");

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(11L);
        assertThat(result.needsInterest()).isFalse();
        assertThat(result.basis()).isEqualTo(RecommendBasis.POPULAR);
        assertThat(withInterest.basis()).isEqualTo(RecommendBasis.POPULAR);
        assertThat(withInterest.items()).isNotEmpty();
        assertThat(fallbacks()).isEqualTo(fallbacks);
    }

    private double fallbacks() {
        var counter = meterRegistry.find("hapbang.chatbot.fallbacks").tag("feature", "lecture_recommend")
                .tag("reason", "model_off").counter();
        return counter == null ? 0 : counter.count();
    }
}
