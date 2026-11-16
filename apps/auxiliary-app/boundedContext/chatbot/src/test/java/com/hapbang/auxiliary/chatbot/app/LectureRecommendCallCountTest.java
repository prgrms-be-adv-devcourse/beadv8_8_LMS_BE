package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.CallRecorder;
import com.hapbang.auxiliary.chatbot.support.CallRecorderConfig;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 강의 추천 한 번이 DB를 몇 번 읽는지와 그때의 결과를 함께 고정한다 (#835 효율 리팩터). 결과는 기존 추천 테스트와 같은 규칙이고,
 * 호출 수는 리팩터 전 값을 넘지 않아야 한다.
 * <p>
 * 회원 2는 강의 20(판매자 100, 축 1)을 샀다. 맞춤 후보 21~26은 판매자 200·300이 3개씩이라 판매자당 2개로 4개만 남고,
 * 강의 27(판매자 400, 색인 안 됨)이 인기순으로 채워진다. 인기순 캐시는 미리 읽어 둔다(요청마다 읽지 않는다).
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class, CallRecorderConfig.class})
class LectureRecommendCallCountTest extends ChatbotIntegrationTestBase {

    @Autowired
    private LectureRecommendUseCase useCase;

    @Autowired
    private LectureRecommendExplainUseCase explainUseCase;

    @Autowired
    private PopularLectureCache popularCache;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @Autowired
    private FakeChatModel chatModel;

    private RecommendRows data;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        chatModel.reset();
        deleteIndex();
        data = new RecommendRows(jdbcTemplate);
        for (long member : List.of(2L, 3L, 100L, 200L, 300L, 400L)) {
            data.member(member);
        }
        lecture(20, 100, "파이썬 입문", FakeEmbeddingModel.axis(1));
        lecture(21, 200, "파이썬 A", FakeEmbeddingModel.withCosine(1, 3, 0.99));
        lecture(22, 200, "파이썬 B", FakeEmbeddingModel.withCosine(1, 3, 0.98));
        lecture(23, 200, "파이썬 C", FakeEmbeddingModel.withCosine(1, 3, 0.97));
        lecture(24, 300, "파이썬 D", FakeEmbeddingModel.withCosine(1, 3, 0.96));
        lecture(25, 300, "파이썬 E", FakeEmbeddingModel.withCosine(1, 3, 0.95));
        lecture(26, 300, "파이썬 F", FakeEmbeddingModel.withCosine(1, 3, 0.94));
        data.lecture(27, 400, "인기 강의");
        data.purchase(2, 2, 20, "OWNED", RecommendRows.AT);
        for (long buyer = 50; buyer < 53; buyer++) {
            data.purchase(buyer, buyer, 27, "OWNED", RecommendRows.AT);
        }
        indexAll(20, 21, 22, 23, 24, 25, 26);
        popularCache.top();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("위젯 경로(맞춤 4개 + 인기순 1개): 결과와 함께 SQL 10회 이하(강의 사본 3회·회원 사본 3회 이하)")
    void widget() {
        CallRecorder.Recorded<LectureRecommendation> recorded = CallRecorder.record(() -> useCase.recommend(2, null));

        LectureRecommendation result = recorded.value();
        assertThat(result.basis()).isEqualTo(RecommendBasis.PERSONALIZED);
        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::title,
                        LectureRecommendation.Item::reason, LectureRecommendation.Item::reasonText)
                .containsExactly(
                        tuple(21L, "파이썬 A", LectureRecommendReason.SIMILAR_TO_PURCHASED, "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(22L, "파이썬 B", LectureRecommendReason.SIMILAR_TO_PURCHASED, "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(24L, "파이썬 D", LectureRecommendReason.SIMILAR_TO_PURCHASED, "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(25L, "파이썬 E", LectureRecommendReason.SIMILAR_TO_PURCHASED, "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(27L, "인기 강의", LectureRecommendReason.POPULAR, "요즘 많이 듣는 강의예요."));
        CallRecorder.Calls calls = recorded.calls();
        assertThat(calls.sql()).as(calls.toString()).hasSizeLessThanOrEqualTo(10);
        assertThat(calls.sqlOn("lecture_copy")).as(calls.toString()).isLessThanOrEqualTo(3);
        assertThat(calls.sqlOn("member_copy")).as(calls.toString()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("버튼 경로(LLM이 하나 고름 + 남은 풀 + 인기순): 결과와 프롬프트의 후보 줄과 함께 SQL 11회 이하(강의 사본 4회 이하)")
    void button() {
        chatModel.reply("""
                {"picks": [{"candidate": 2, "reason": "이 강의가 좋아요"}]}""");

        CallRecorder.Recorded<LectureRecommendExplainUseCase.Explained> recorded =
                CallRecorder.record(() -> explainUseCase.recommend(2, null));

        LectureRecommendation result = recorded.value().recommendation();
        assertThat(recorded.value().fallback()).isFalse();
        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::title,
                        LectureRecommendation.Item::reasonText)
                .containsExactly(
                        tuple(22L, "파이썬 B", "이 강의가 좋아요"),
                        tuple(21L, "파이썬 A", "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(24L, "파이썬 D", "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(25L, "파이썬 E", "구매하신 「파이썬 입문」 강의와 비슷해요."),
                        tuple(27L, "인기 강의", "요즘 많이 듣는 강의예요."));
        assertThat(chatModel.lastPrompt().getContents())
                .contains("제목: 파이썬 A | 소개: 소개 파이썬 A | 추천 근거: ")
                .contains("제목: 파이썬 E | 소개: 소개 파이썬 E | 추천 근거: ")
                .doesNotContain("파이썬 입문");
        CallRecorder.Calls calls = recorded.calls();
        assertThat(calls.sql()).as(calls.toString()).hasSizeLessThanOrEqualTo(11);
        assertThat(calls.sqlOn("lecture_copy")).as(calls.toString()).isLessThanOrEqualTo(4);
        assertThat(calls.sqlOn("member_copy")).as(calls.toString()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("신호가 없는 회원(인기순만): 결과와 함께 SQL 7회 이하(강의 사본 2회·회원 사본 2회 이하)")
    void popularOnly() {
        CallRecorder.Recorded<LectureRecommendation> recorded = CallRecorder.record(() -> useCase.recommend(3, null));

        LectureRecommendation result = recorded.value();
        assertThat(result.needsInterest()).isTrue();
        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::title,
                        LectureRecommendation.Item::reason)
                .containsExactly(
                        tuple(27L, "인기 강의", LectureRecommendReason.POPULAR),
                        tuple(20L, "파이썬 입문", LectureRecommendReason.POPULAR),
                        tuple(26L, "파이썬 F", LectureRecommendReason.POPULAR),
                        tuple(25L, "파이썬 E", LectureRecommendReason.POPULAR),
                        tuple(23L, "파이썬 C", LectureRecommendReason.POPULAR));
        CallRecorder.Calls calls = recorded.calls();
        assertThat(calls.sql()).as(calls.toString()).hasSizeLessThanOrEqualTo(7);
        assertThat(calls.sqlOn("lecture_copy")).as(calls.toString()).isLessThanOrEqualTo(2);
        assertThat(calls.sqlOn("member_copy")).as(calls.toString()).isLessThanOrEqualTo(2);
    }

    private void lecture(long id, long sellerId, String title, float[] vector) {
        data.lecture(id, sellerId, title);
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
        ElasticsearchTestcontainersConfig.deleteIndex("chatbot-lectures-test-8");
    }
}
