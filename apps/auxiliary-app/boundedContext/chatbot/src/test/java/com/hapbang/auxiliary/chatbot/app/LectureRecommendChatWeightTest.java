package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 자유 질문 Tool의 관심사 비율은 {@code chat-interest-weight}(0.7 = 운영 값), 위젯·버튼은 {@code interest-weight}(0.3) (#1007).
 * <p>
 * 회원은 축 0 강의(기준 강의)를 샀고 관심사 "음악"은 축 1이다. A 강의는 축 0 쪽, B 강의는 축 1 쪽이라
 * α 0.3이면 A가, α 0.7이면 B가 위로 온다(두 강의는 구매가 없어 인기 차이가 없다).
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
@TestPropertySource(properties = "hapbang.chatbot.recommend.lecture.chat-interest-weight=0.7")
class LectureRecommendChatWeightTest extends ChatbotIntegrationTestBase {

    private static final long MEMBER = 1L;
    private static final long BASE = 1L;
    private static final long NEAR_PURCHASE = 2L;
    private static final long NEAR_INTEREST = 3L;
    private static final String INTEREST = "음악";

    @Autowired
    private LectureRecommendUseCase useCase;

    @Autowired
    private LectureRecommendTools tools;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        deleteIndex();
        RecommendRows data = new RecommendRows(jdbcTemplate);
        data.member(MEMBER);
        for (long seller : List.of(100L, 200L, 300L)) {
            data.member(seller);
        }
        lecture(data, BASE, 100L, "기준 강의", FakeEmbeddingModel.axis(0));
        lecture(data, NEAR_PURCHASE, 200L, "A 강의", FakeEmbeddingModel.withCosine(0, 2, 0.9));
        lecture(data, NEAR_INTEREST, 300L, "B 강의", FakeEmbeddingModel.withCosine(1, 2, 0.9));
        embedding.put(FakeEmbeddingModel.query(INTEREST), FakeEmbeddingModel.axis(1));
        data.purchase(1L, MEMBER, BASE, "OWNED", RecommendRows.AT);
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : List.of(BASE, NEAR_PURCHASE, NEAR_INTEREST)) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        assertThat(handler.handle(docs)).isEmpty();
        index.refresh(IndexType.LECTURE);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("위젯(α 0.3)은 산 강의에 가까운 A가 먼저, 자유 질문 Tool(α 0.7)은 말한 관심사에 가까운 B가 먼저다")
    void chatUsesItsOwnInterestWeight() {
        assertThat(useCase.recommend(MEMBER, INTEREST).items().getFirst().lectureId()).isEqualTo(NEAR_PURCHASE);
        assertThat(useCase.recommendInChat(MEMBER, INTEREST).items().getFirst().lectureId()).isEqualTo(NEAR_INTEREST);

        ToolContext context = new ToolContext(ChatToolContext.of(MEMBER, List.of(), new ChatCardCollector()));
        assertThat(tools.recommendLectures(INTEREST, context).items().getFirst().lectureId()).isEqualTo(NEAR_INTEREST);
    }

    @Test
    @DisplayName("버튼 경로의 맞춤 후보 풀은 위젯과 같은 α 0.3이다")
    void buttonPoolKeepsWidgetWeight() {
        LectureRecommendUseCase.Pool pool = useCase.pool(MEMBER, List.of(INTEREST), 20);

        assertThat(pool.ranked()).extracting(LectureRanker.Ranked::lectureId).containsExactly(NEAR_PURCHASE, NEAR_INTEREST);
    }

    private void lecture(RecommendRows data, long id, long sellerId, String title, float[] vector) {
        data.lecture(id, sellerId, title);
        embedding.put(title, vector);
    }

    private static void deleteIndex() throws Exception {
        ElasticsearchTestcontainersConfig.deleteIndex("chatbot-lectures-test-8");
    }
}
