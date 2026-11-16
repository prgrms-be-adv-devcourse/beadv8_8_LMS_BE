package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.config.ChatbotLlmConfig;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 버튼 경로 리랭킹에 요청 회원의 공개 취향을 넣는 설정(#948, {@code rerank.taste=PUBLIC}, {@code rerank.temperature=0}).
 * 실제 DB·ES와 가짜 임베딩·채팅 모델로 강의 버튼 경로를 부른다. 기본값({@code NONE})의 프롬프트는 {@code LectureRecommendButtonTest}가 본다.
 * <p>
 * 회원은 모두 강의 1(축 0)을 샀다. 맞춤 후보 10~16은 축 0에 가까운 순서이고 판매자가 모두 다르다. 회원 ID는 프롬프트의 다른 숫자와
 * 겹치지 않는 값으로 둔다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
@TestPropertySource(properties = {
        "hapbang.chatbot.recommend.rerank.taste=PUBLIC",
        "hapbang.chatbot.recommend.rerank.temperature=0"
})
class RecommendTasteButtonTest extends ChatbotIntegrationTestBase {

    private static final long USER = 90017;
    private static final long INFLUENCER = 90018;
    private static final String PICKS = """
            {"picks": [{"candidate": 3, "reason": "셋째"}, {"candidate": 1, "reason": "첫째"}]}""";

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
        data.member(100);
        lecture(1, 100, "회원이 산 강의", FakeEmbeddingModel.axis(0));
        for (int i = 0; i < 7; i++) {
            data.member(101 + i);
            lecture(10 + i, 101 + i, "후보 강의 " + (10 + i), FakeEmbeddingModel.withCosine(0, 1, 0.99 - i * 0.01));
        }
        indexAll(1, 10, 11, 12, 13, 14, 15, 16);
        data.member(USER);
        data.purchase(1, USER, 1, "OWNED", RecommendRows.AT);
        data.influencer(INFLUENCER, List.of("GAME"), "게임 실황을 방송합니다.\n주말에 합방해요.");
        data.purchase(2, INFLUENCER, 1, "OWNED", RecommendRows.AT);
        chatModel.reply(PICKS);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("관심사 없는 일반 회원은 넣을 취향이 없어 고르지 않는다: 코드 순서 5개 그대로, 프롬프트에 '요청한 사람'이 없다")
    void userWithoutInterestKeepsCodeOrder() {
        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(USER, null);

        assertThat(result.fallback()).isFalse();
        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(chatModel.lastPrompt().getUserMessage().getText()).doesNotContain("요청한 사람");
        assertThat(chatModel.lastPrompt().getSystemMessage().getText()).contains("모두, 주어진 순서대로");
    }

    @Test
    @DisplayName("관심사를 입력한 일반 회원은 관심사만 넣고 LLM이 고른 순서를 따른다. 회원 ID·산 강의 제목·분야·자기소개는 없다")
    void userInterestOnly() {
        embedding.put(FakeEmbeddingModel.query("게임 방송"), FakeEmbeddingModel.axis(0));

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(USER, "게임 방송");

        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId).startsWith(12L, 10L);
        String prompt = prompt();
        assertThat(prompt).contains("요청한 사람", "- 관심사: 게임 방송");
        assertThat(prompt).doesNotContain(String.valueOf(USER), "회원이 산 강의", "활동 분야", "자기소개:");
    }

    @Test
    @DisplayName("인플루언서는 관심사가 없어도 공개 프로필(분야·자기소개 한 줄)을 넣고 LLM이 고른 순서를 따른다. 회원 ID·산 강의 제목은 없다")
    void influencerPublicProfile() {
        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(INFLUENCER, null);

        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId).startsWith(12L, 10L);
        String prompt = prompt();
        assertThat(prompt).contains("- 활동 분야: ", "- 자기소개: 게임 실황을 방송합니다. 주말에 합방해요.");
        assertThat(prompt).doesNotContain(String.valueOf(INFLUENCER), "회원이 산 강의", "- 관심사:");
    }

    @Test
    @DisplayName("강등된 회원에게 남은 분야·자기소개는 넣지 않는다(공개 프로필이 아니다). 관심사가 없으면 고르지 않는다")
    void demotedMemberHasNoProfile() {
        jdbcTemplate.update("UPDATE chatbot.member_copy SET role = 'USER' WHERE id = ?", INFLUENCER);

        LectureRecommendExplainUseCase.Explained result = explainUseCase.recommend(INFLUENCER, null);

        assertThat(result.recommendation().items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(prompt()).doesNotContain("요청한 사람", "게임 실황");
    }

    @Test
    @DisplayName("rerank.temperature=0이 리랭킹 요청의 온도로 실린다")
    void temperatureFromProperties() {
        explainUseCase.recommend(INFLUENCER, null);

        assertThat(chatModel.lastPrompt().getOptions().getTemperature()).isEqualTo(0.0);
    }

    private String prompt() {
        return chatModel.lastPrompt().getInstructions().stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
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
