package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.TasteVectors;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 강의 벡터에 제목 벡터를 섞는다 (#1007, {@code title-weight} 0.3 = 운영 값). 비율 0(기본값)은 {@link LectureIndexTest}가 본다.
 * <p>
 * 가짜 임베딩 표: 글("제목\n소개…")은 축 3·4, 제목만은 축 0·1이라 섞였는지 벡터로 구분된다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
@TestPropertySource(properties = "hapbang.chatbot.recommend.lecture.title-weight=0.3")
class LectureIndexTitleWeightTest extends ChatbotIntegrationTestBase {

    private static final double W = 0.3;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private LectureIndexReconciler reconciler;

    @Autowired
    private IndexTaskRunner runner;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private LectureRecommendStore store;

    @Autowired
    private FakeEmbeddingModel embedding;

    private RecommendRows data;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        deleteIndex();
        data = new RecommendRows(jdbcTemplate);
        embedding.put("스프링 입문", FakeEmbeddingModel.axis(0));
        embedding.put("파이썬 기초", FakeEmbeddingModel.axis(1));
        embedding.put("스프링 입문\n", FakeEmbeddingModel.axis(3));
        embedding.put("파이썬 기초\n", FakeEmbeddingModel.axis(4));
        data.lecture(1, 100, "스프링 입문", "웹 개발", List.of("1강 설정"), true, false, false);
        data.lecture(2, 200, "파이썬 기초", "데이터 분석", List.of(), true, false, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("글과 제목을 한 번의 공급자 호출로 임베딩하고(글 N개 뒤에 제목 N개) (1−w)·글 + w·제목으로 섞어 크기 1로 저장한다. "
            + "해시에 w가 들어간다")
    void mixesTitleInOneCall() {
        assertThat(handle("1", "2")).isEmpty();

        assertThat(embedding.calls()).isEqualTo(1);
        assertThat(embedding.texts()).containsExactly(lecture(1).indexText(), lecture(2).indexText(), "스프링 입문", "파이썬 기초");
        assertSameDirection(vector("1"), TasteVectors.mix(FakeEmbeddingModel.axis(3), FakeEmbeddingModel.axis(0), W));
        assertSameDirection(vector("2"), TasteVectors.mix(FakeEmbeddingModel.axis(4), FakeEmbeddingModel.axis(1), W));
        // 취향 벡터는 신호 강의 벡터를 정규화 없이 가중 평균한다. 크기가 1이 아니면 신호 가중치(#867)가 강의마다 달라진다
        assertUnit(vector("1"));
        assertUnit(vector("2"));
        assertThat(stored("1").textHash()).isEqualTo(lecture(1).textHash(W)).isNotEqualTo(lecture(1).textHash(0));
    }

    @Test
    @DisplayName("배포 전(w 0)에 색인한 문서는 해시가 달라 정합 점검이 모두 요청하고, 실행기가 섞은 벡터로 바꾼다. 다음 점검은 0건")
    void reconcileReindexesDocsFromWeightZero() {
        indexAsBeforeDeploy(1);
        indexAsBeforeDeploy(2);

        assertThat(reconciler.check()).isEqualTo(2);
        assertThat(runner.runOnce()).isEqualTo(2);
        index.refresh(IndexType.LECTURE);

        assertThat(embedding.calls()).isEqualTo(1);
        assertSameDirection(vector("1"), TasteVectors.mix(FakeEmbeddingModel.axis(3), FakeEmbeddingModel.axis(0), W));
        assertThat(reconciler.check()).isZero();
    }

    @Test
    @DisplayName("묶음 임베딩(글 + 제목)이 실패하면 그 강의들만 실패로 돌려준다")
    void embeddingFailureFailsBatch() {
        RuntimeException down = new IllegalStateException("임베딩 공급자 장애");
        embedding.failWith(down);

        assertThat(handle("1", "2")).containsOnlyKeys("1", "2").containsValue(down);
    }

    // #1007 전 처리기처럼 글 벡터 그대로, 비율 0의 해시로 넣는다
    private void indexAsBeforeDeploy(long id) {
        LectureSnapshot lecture = lecture(id);
        float[] textVector = id == 1 ? FakeEmbeddingModel.axis(3) : FakeEmbeddingModel.axis(4);
        assertThat(index.upsertAll(IndexType.LECTURE, List.of(new RecommendVectorIndex.Upsert(String.valueOf(id), textVector,
                LectureIndexDocument.of(lecture, 0).allFields())))).isEmpty();
        index.refresh(IndexType.LECTURE);
    }

    private static void assertUnit(float[] vector) {
        assertThat(Math.sqrt(TasteVectors.dot(vector, vector))).isCloseTo(1.0, within(1e-6));
    }

    private static void assertSameDirection(float[] actual, float[] expected) {
        assertThat(TasteVectors.cosine(actual, expected)).isGreaterThan(0.9999);
    }

    private float[] vector(String id) {
        return index.vectors(IndexType.LECTURE, List.of(id)).get(id);
    }

    private LectureIndexDocument stored(String id) {
        return index.documents(IndexType.LECTURE, List.of(id), LectureIndexDocument.class).get(id);
    }

    private LectureSnapshot lecture(long id) {
        return store.lectures(List.of(id)).get(id);
    }

    private Map<String, RuntimeException> handle(String... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (String id : ids) {
            docs.put(id, IndexAction.UPSERT);
        }
        Map<String, RuntimeException> failures = handler.handle(docs);
        index.refresh(IndexType.LECTURE);
        return failures;
    }

    private static void deleteIndex() throws Exception {
        ElasticsearchTestcontainersConfig.deleteIndex("chatbot-lectures-test-8");
    }
}
