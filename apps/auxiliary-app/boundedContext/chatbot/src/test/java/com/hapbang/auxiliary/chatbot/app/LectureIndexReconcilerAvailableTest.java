package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 강의 색인 점검은 색인 실행기(강의 색인 처리기)가 요청을 처리할 수 있는 실행에서만 돈다 (#835).
 * ES는 있고 임베딩 모델이 없는 실행에서 점검이 돌면 실행기가 가져가지 않는 요청만 쌓인다.
 */
class LectureIndexReconcilerAvailableTest {

    private final RecommendVectorIndex index = mock(RecommendVectorIndex.class);
    private final ChatbotEmbedder embedder = mock(ChatbotEmbedder.class);
    private final LectureIndexReconciler reconciler = new LectureIndexReconciler(mock(LectureRecommendStore.class), index,
            embedder, mock(IndexTaskStore.class), mock(ChatbotLectureRecommendProperties.class), Clock.systemUTC());

    @ParameterizedTest(name = "ES {0}, 임베딩 모델 {1} → {2}")
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"
    })
    @DisplayName("ES와 임베딩 모델이 모두 있어야 점검한다")
    void availableOnlyWithIndexAndEmbedder(boolean indexAvailable, boolean embedderAvailable, boolean expected) {
        doReturn(indexAvailable).when(index).available();
        doReturn(embedderAvailable).when(embedder).available();

        assertThat(reconciler.available()).isEqualTo(expected);
    }
}
