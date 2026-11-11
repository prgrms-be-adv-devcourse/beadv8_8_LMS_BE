package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.micrometer.core.instrument.MeterRegistry;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 버튼 경로의 LLM 이유 문장(#718)을 실제 DB·ES와 가짜 채팅 모델로 확인한다. 후보는 코드가 정하고 LLM은 문장만 바꾼다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class LectureRecommendExplainUseCaseTest extends ChatbotIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String EMOJI = "😀";
    private static final String PURCHASED_TITLE = "회원이 산 강의";

    @Autowired
    private LectureRecommendExplainUseCase explainUseCase;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @Autowired
    private FakeChatModel chat;

    @Autowired
    private MeterRegistry meterRegistry;

    private RecommendRows data;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        chat.reset();
        deleteIndex();
        data = new RecommendRows(jdbcTemplate);
        for (long id : List.of(1L, 100L, 200L, 300L)) {
            data.member(id);
        }
        // 회원 1이 강의 10을 샀다. 후보는 가까운 순으로 11(판매자 200), 12(판매자 300)
        lecture(10, 100, PURCHASED_TITLE, "산 강의 소개", FakeEmbeddingModel.axis(0));
        lecture(11, 200, "추천 강의 A", "가".repeat(299) + EMOJI + "꼬리", FakeEmbeddingModel.withCosine(0, 1, 0.95));
        lecture(12, 300, "추천 강의 B", "소개 B", FakeEmbeddingModel.withCosine(0, 1, 0.90));
        data.purchase(1, 1, 10, "OWNED", RecommendRows.AT);
        indexAll(10, 11, 12);
    }

    @AfterEach
    void tearDown() throws Exception {
        chat.reset();
        deleteIndex();
    }

    @Test
    @DisplayName("후보 목록은 코드 순서 그대로이고 문장만 바꾼다: 후보 밖 강의는 버리고, 빈 문장·빠진 강의는 정해진 문장, 긴 문장은 코드포인트로 자른다")
    void explained() {
        String longReason = "나".repeat(79) + EMOJI + "넘침";
        chat.answerWith("""
                [{"lectureId": 11, "reason": "%s"},
                 {"lectureId": 12, "reason": "   "},
                 {"lectureId": 999, "reason": "후보 밖 강의"}]
                """.formatted(longReason));

        LectureRecommendation result = explainUseCase.recommend(1, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::reasonText)
                .containsExactly(
                        tuple(11L, "나".repeat(79) + EMOJI),
                        tuple(12L, "구매하신 「" + PURCHASED_TITLE + "」 강의와 비슷해요."));
        assertThat(chat.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("프롬프트에는 후보 강의의 제목·소개만 넣는다: 회원이 산 강의 제목은 없고, 긴 소개는 이모지를 반으로 자르지 않는다")
    void promptHasOnlyCandidates() {
        chat.answerWith("[]");

        explainUseCase.recommend(1, null);

        String prompt = chat.lastPrompt().getUserMessage().getText();
        assertThat(prompt).contains("강의 ID: 11", "추천 강의 A", "강의 ID: 12", "추천 강의 B", "가".repeat(299) + EMOJI)
                .doesNotContain(PURCHASED_TITLE, "꼬리");
        // 짝 잃은 서로게이트는 코드포인트로 세어도 서로게이트 구간에 남는다
        assertThat(prompt.codePoints()).noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    @Test
    @DisplayName("LLM이 실패하면 정해진 문장으로 답하고 fallback(error)으로 센다. 후보 목록은 그대로 나간다")
    void llmFailureKeepsFixedSentences() {
        chat.mode(FakeChatModel.Mode.FAIL);
        double fallbacks = fallbacks("error");

        LectureRecommendation result = explainUseCase.recommend(1, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::reasonText)
                .containsExactly(
                        tuple(11L, "구매하신 「" + PURCHASED_TITLE + "」 강의와 비슷해요."),
                        tuple(12L, "구매하신 「" + PURCHASED_TITLE + "」 강의와 비슷해요."));
        assertThat(fallbacks("error")).isEqualTo(fallbacks + 1);
    }

    @Test
    @DisplayName("추천할 강의가 없으면(숨김 회원) LLM을 부르지 않는다")
    void emptyDoesNotCallLlm() {
        data.member(2, "WITHDRAWN");

        LectureRecommendation result = explainUseCase.recommend(2, null);

        assertThat(result.items()).isEmpty();
        assertThat(chat.calls()).isZero();
    }

    private double fallbacks(String reason) {
        var counter = meterRegistry.find("hapbang.chatbot.fallbacks").tag("feature", "lecture_recommend")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void lecture(long id, long sellerId, String title, String description, float[] vector) {
        data.lecture(id, sellerId, title, description, List.of(), true, false, false);
        embedding.put(title, vector);
    }

    private void indexAll(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        assertThat(handler.handle(docs)).isEmpty();
        index.refresh(IndexType.LECTURE);
    }

    private static void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-lectures-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
