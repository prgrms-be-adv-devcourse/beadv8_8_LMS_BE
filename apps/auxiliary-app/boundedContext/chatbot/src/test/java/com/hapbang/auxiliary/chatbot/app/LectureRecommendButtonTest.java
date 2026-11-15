package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.config.ChatbotLlmConfig;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 추천 버튼 경로: 쿼리 확장 + 맞춤 후보 풀 + 리랭킹·이유 + 채우기 (#720). 실제 DB·ES와 가짜 임베딩·채팅 모델을 쓴다.
 * <p>
 * 회원 1은 강의 1(축 0)을 샀다. 맞춤 후보 10~16은 축 0에 가까운 순서(코사인 0.99~0.93)이고 판매자가 모두 다르다.
 * 강의 30은 색인하지 않은 인기 강의(구매 3)다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class LectureRecommendButtonTest extends ChatbotIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private LectureRecommendExplainUseCase explainUseCase;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Qualifier(ChatbotLlmConfig.CIRCUIT_BREAKER)
    private CircuitBreaker circuitBreaker;

    private RecommendRows data;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        chatModel.reset();
        circuitBreaker.reset();
        deleteIndex();
        data = new RecommendRows(jdbcTemplate);
        data.member(1);
        data.member(100);
        lecture(1, 100, "기준 강의", FakeEmbeddingModel.axis(0));
        data.purchase(1, 1, 1, "OWNED", RecommendRows.AT);
        for (int i = 0; i < 7; i++) {
            data.member(101 + i);
            lecture(10 + i, 101 + i, "후보 강의 " + (10 + i), FakeEmbeddingModel.withCosine(0, 1, 0.99 - i * 0.01));
        }
        data.member(108);
        data.lecture(30, 108, "인기 강의");
        for (int i = 0; i < 3; i++) {
            data.purchase(3000 + i, 9000 + i, 30, "OWNED", RecommendRows.AT);
        }
        indexAll(1, 10, 11, 12, 13, 14, 15, 16);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("LLM이 고른 순번 → 남은 풀(코드 순서)로 5개. 범위 밖·중복·빈 문장·순번을 말한 문장은 버리고, 이유는 80자에서 자른다. "
            + "고르지 않은 강의는 이유 코드 문장. LLM 호출은 1회(관심사 없음)")
    void rerankPicksThenRestOfPool() {
        chatModel.reply("""
                {"picks": [
                  {"candidate": 3, "reason": "%s"},
                  {"candidate": 99, "reason": "후보 밖"},
                  {"candidate": 3, "reason": "중복"},
                  {"candidate": 1, "reason": "후보 2와 비슷해요"},
                  {"candidate": 2, "reason": "  "},
                  {"candidate": 5, "reason": "실습이 많아요"}]}""".formatted("가".repeat(120)));

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(1, null);

        assertThat(result.fallback()).isFalse();
        assertThat(result.recommendation().basis()).isEqualTo(RecommendBasis.PERSONALIZED);
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(12L, 14L, 10L, 11L, 13L);
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::reasonText)
                .startsWith("가".repeat(80), "실습이 많아요");
        assertThat(result.recommendation().items().get(2).reasonText())
                .isEqualTo(LectureRecommendReason.SIMILAR_TO_PURCHASED.sentence("기준 강의"));
        assertThat(chatModel.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("풀이 5개보다 적으면 LLM이 고른 강의 → 남은 풀(코드 순서) → 인기순으로 채운다(채운 강의는 POPULAR 이유 문장). "
            + "회원 3은 10~14를 사서 풀은 1·15·16이다")
    void fillsWithPopularAfterRerank() {
        data.member(3);
        for (int i = 0; i < 5; i++) {
            data.purchase(300 + i, 3, 10 + i, "OWNED", RecommendRows.AT);
        }
        chatModel.reply("""
                {"picks": [{"candidate": 2, "reason": "이 강의가 좋아요"}]}""");

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(3, null);

        assertThat(result.fallback()).isFalse();
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId)
                .startsWith(15L, 1L, 16L, 30L);
        assertThat(result.recommendation().items().get(3).reason()).isEqualTo(LectureRecommendReason.POPULAR);
    }

    @Test
    @DisplayName("LLM이 실패하면 코드 순서 5개 + 이유 코드 문장, fallback=true, fallbacks{lecture_button, error}")
    void llmFailureFallsBackToCodeOrder() {
        chatModel.mode(FakeChatModel.Mode.FAIL);
        double before = fallbacks("error");

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(1, null);

        assertThat(result.fallback()).isTrue();
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(result.recommendation().items()).allSatisfy(item ->
                assertThat(item.reasonText()).isEqualTo(LectureRecommendReason.SIMILAR_TO_PURCHASED.sentence("기준 강의")));
        assertThat(fallbacks("error")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("형식 오류(JSON이 아닌 답)는 fallback=true로 답하고 fallbacks{reason=format}으로 세며, 회로 실패 수에는 넣지 않는다")
    void formatErrorIsNotCircuitFailure() {
        chatModel.reply("형식이 아닌 글");
        double before = fallbacks("format");

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(1, null);

        assertThat(result.fallback()).isTrue();
        assertThat(result.recommendation().items()).hasSize(5);
        assertThat(fallbacks("format")).isEqualTo(before + 1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("쓸 만한 답이 하나도 없으면 fallback=true(일부만 쓸 만하면 false)")
    void noUsableAnswerIsFallback() {
        chatModel.reply("""
                {"picks": [{"candidate": 99, "reason": "후보 밖"}]}""");

        assertThat(explainUseCase.recommend(1, null).fallback()).isTrue();
    }

    @Test
    @DisplayName("맞춤 후보 풀이 비면(신호도 관심사도 없음) LLM을 부르지 않고 인기순·POPULAR")
    void emptyPoolSkipsLlm() {
        data.member(4);

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(4, null);

        assertThat(chatModel.calls()).isZero();
        assertThat(result.fallback()).isFalse();
        assertThat(result.recommendation().basis()).isEqualTo(RecommendBasis.POPULAR);
        assertThat(result.recommendation().items().getFirst().lectureId()).isEqualTo(30L);
    }

    @Test
    @DisplayName("관심사가 있으면 쿼리 확장 1회 + 리랭킹 1회. 쿼리 확장이 실패해도 관심사 원문으로 계속한다")
    void interestCallsQueryExpansionThenRerank() {
        embedding.put(FakeEmbeddingModel.query("스프링"), FakeEmbeddingModel.axis(0));
        data.member(5);
        chatModel.mode(FakeChatModel.Mode.FAIL);
        double expansionFallbacks = count("hapbang.chatbot.fallbacks", "query_expansion", "error");

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(5, "스프링");

        assertThat(chatModel.calls()).isEqualTo(2);
        assertThat(count("hapbang.chatbot.fallbacks", "query_expansion", "error")).isEqualTo(expansionFallbacks + 1);
        assertThat(result.recommendation().basis()).isEqualTo(RecommendBasis.PERSONALIZED);
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::reason)
                .contains(LectureRecommendReason.MATCHES_INTEREST);
    }

    @Test
    @DisplayName("리랭킹 프롬프트에는 후보의 제목·소개와 근거 설명만 있고 회원 ID·회원이 산 강의(이유 문장의 기준 강의 제목)는 없다")
    void promptHasNoMemberInfo() {
        // 회원 ID는 프롬프트의 다른 숫자와 겹치지 않는 값으로 둔다
        long member = 90017;
        data.member(member);
        lecture(2, 100, "회원이 산 강의", FakeEmbeddingModel.axis(0));
        indexAll(2);
        data.purchase(2, member, 2, "OWNED", RecommendRows.AT);
        chatModel.reply("""
                {"picks": []}""");

        explainUseCase.recommend(member, null);

        String prompt = chatModel.lastPrompt().getInstructions().stream().map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(prompt).contains("후보 강의 10", "소개 후보 강의 10", LectureRecommendReason.SIMILAR_TO_PURCHASED.description());
        assertThat(prompt).doesNotContain("회원이 산 강의", "회원 ID", String.valueOf(member));
    }

    @Test
    @DisplayName("긴 이유 문장과 프롬프트의 긴 소개는 코드포인트로 잘라 이모지를 반으로 자르지 않는다")
    void cutsByCodePoints() {
        String emoji = "😀";
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET description = ? WHERE id = 10", "가".repeat(299) + emoji + "꼬리");
        chatModel.reply("""
                {"picks": [{"candidate": 1, "reason": "%s"}]}""".formatted("나".repeat(79) + emoji + "넘침"));

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(1, null);

        assertThat(result.recommendation().items().getFirst().reasonText()).isEqualTo("나".repeat(79) + emoji);
        String prompt = chatModel.lastPrompt().getUserMessage().getText();
        assertThat(prompt).contains("가".repeat(299) + emoji).doesNotContain("꼬리");
        assertThat(prompt.codePoints()).noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    private double fallbacks(String reason) {
        return count("hapbang.chatbot.fallbacks", "lecture_button", reason);
    }

    private double count(String name, String feature, String reason) {
        var counter = meterRegistry.find(name).tag("feature", feature).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
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
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-lectures-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
