package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/** 강의 추천 색인 처리기와 정합 점검을 실제 DB·ES(9.5.5)로 확인한다 (#718) */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class LectureIndexTest extends ChatbotIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private LectureIndexReconciler reconciler;

    @Autowired
    private IndexTaskRunner runner;

    @Autowired
    private IndexTaskStore taskStore;

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
        embedding.put("스프링", FakeEmbeddingModel.axis(0));
        embedding.put("파이썬", FakeEmbeddingModel.axis(1));
        embedding.put("자바", FakeEmbeddingModel.axis(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("새 강의: 제목+소개+콘텐츠 제목을 한 번의 공급자 호출로 임베딩해 벡터와 상태 필드·글 해시를 넣는다(판매 중이 아니어도 색인)")
    void indexesNewLecturesInOneBatch() {
        data.lecture(1, 100, "스프링 입문", "웹 개발", List.of("1강 설정", "2강 MVC"), true, false, false);
        data.lecture(2, 200, "파이썬 기초", null, List.of(), false, true, true);

        assertThat(handle(docs("1", "2"))).isEmpty();

        assertThat(embedding.calls()).isEqualTo(1);
        assertThat(embedding.embeddedTexts()).isEqualTo(2);
        Map<String, LectureIndexDocument> stored = index.documents(IndexType.LECTURE, List.of("1", "2"),
                LectureIndexDocument.class);
        assertThat(stored.get("1")).isEqualTo(new LectureIndexDocument(true, false, false, 100L,
                lecture(1).textHash()));
        assertThat(stored.get("2")).isEqualTo(new LectureIndexDocument(false, true, true, 200L, lecture(2).textHash()));
        assertThat(lecture(1).indexText()).isEqualTo("스프링 입문\n웹 개발\n1강 설정\n2강 MVC");
        assertThat(index.vectors(IndexType.LECTURE, List.of("1")).get("1")).containsExactly(FakeEmbeddingModel.axis(0));
    }

    @Test
    @DisplayName("임베딩 글이 같고 상태만 바뀌면 다시 임베딩하지 않고 상태 필드만 바꾼다(벡터 그대로)")
    void stateOnlyChangeSkipsEmbedding() {
        data.lecture(1, 100, "스프링 입문");
        handle(docs("1"));
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET on_sale = FALSE, missing_in_source = TRUE WHERE id = 1");
        embedding.put("스프링", FakeEmbeddingModel.axis(3));

        assertThat(handle(docs("1"))).isEmpty();

        assertThat(embedding.calls()).isEqualTo(1);
        LectureIndexDocument stored = index.documents(IndexType.LECTURE, List.of("1"), LectureIndexDocument.class).get("1");
        assertThat(stored.onSale()).isFalse();
        assertThat(stored.missingInSource()).isTrue();
        assertThat(index.vectors(IndexType.LECTURE, List.of("1")).get("1")).containsExactly(FakeEmbeddingModel.axis(0));
    }

    @Test
    @DisplayName("임베딩 글(콘텐츠 제목 포함)이 바뀌면 다시 임베딩한다")
    void textChangeReembeds() {
        data.lecture(1, 100, "스프링 입문");
        handle(docs("1"));
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET content_titles = '[\"새 콘텐츠\"]'::jsonb WHERE id = 1");
        embedding.put("스프링", FakeEmbeddingModel.axis(3));

        assertThat(handle(docs("1"))).isEmpty();

        assertThat(embedding.calls()).isEqualTo(2);
        assertThat(index.vectors(IndexType.LECTURE, List.of("1")).get("1")).containsExactly(FakeEmbeddingModel.axis(3));
    }

    @Test
    @DisplayName("묶음 임베딩이 실패하면 새로 임베딩할 문서만 실패로 돌려주고, 상태만 바꾸는 문서·사본에 없는 문서는 성공이다")
    void embeddingFailureFailsOnlyEmbeddedDocs() {
        data.lecture(1, 100, "스프링 입문");
        handle(docs("1"));
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET on_sale = FALSE WHERE id = 1");
        data.lecture(2, 100, "파이썬 기초");
        RuntimeException failure = new IllegalStateException("임베딩 장애");
        embedding.failWith(failure);

        Map<String, RuntimeException> failures = handle(docs("1", "2", "999"));

        assertThat(failures).containsOnlyKeys("2").containsEntry("2", failure);
        assertThat(index.documents(IndexType.LECTURE, List.of("1"), LectureIndexDocument.class).get("1").onSale()).isFalse();
    }

    @Test
    @DisplayName("사본을 바꾼 트랜잭션이 적은 요청을 실제 실행기가 처리한다(처리기는 지금 쓸 수 있는 LECTURE 처리기)")
    void runnerUsesHandler() {
        data.lecture(1, 100, "스프링 입문");
        taskStore.add(IndexType.LECTURE, "1", IndexAction.UPSERT, CopyTimes.now(clock));

        assertThat(handler.available()).isTrue();
        assertThat(runner.runOnce()).isEqualTo(1);
        index.refresh(IndexType.LECTURE);

        assertThat(index.documents(IndexType.LECTURE, List.of("1"), LectureIndexDocument.class)).containsKey("1");
        assertThat(rows.indexTasks()).allSatisfy(task -> assertThat(task.get("done_at")).isNotNull());
    }

    @Test
    @DisplayName("정합 점검: index가 비었으면 모든 강의를 요청한다(처음 전체 색인). 다 맞추면 다음 점검은 0건")
    void reconcileRequestsAllWhenEmpty() {
        data.lecture(1, 100, "스프링 입문");
        data.lecture(2, 100, "파이썬 기초");
        data.lecture(3, 200, "자바 기초");

        assertThat(reconciler.check()).isEqualTo(3);
        assertThat(pendingDocs()).containsExactlyInAnyOrder("1", "2", "3");

        assertThat(runner.runOnce()).isEqualTo(3);
        index.refresh(IndexType.LECTURE);
        assertThat(reconciler.check()).isZero();
    }

    @Test
    @DisplayName("정합 점검: 요청 없이 어긋난 강의(상태가 다름·글이 다름·문서 없음)만 요청한다")
    void reconcileRequestsOnlyStale() {
        data.lecture(1, 100, "스프링 입문");
        data.lecture(2, 100, "파이썬 기초");
        data.lecture(3, 200, "자바 기초");
        data.lecture(4, 200, "자바 심화");
        handle(docs("1", "2", "3", "4"));
        jdbcTemplate.update("DELETE FROM chatbot.index_task");
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET deleted = TRUE WHERE id = 1");
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET title = '파이썬 기초 개정판' WHERE id = 2");
        index.delete(IndexType.LECTURE, "3");
        index.refresh(IndexType.LECTURE);

        assertThat(reconciler.check()).isEqualTo(3);
        assertThat(pendingDocs()).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    @DisplayName("정합 점검: 끝나지 않은 요청이 이미 있는 문서는 다시 적지 않는다. 끝난 요청만 있는 문서는 어긋나면 적는다")
    void reconcileSkipsPendingDocs() {
        data.lecture(1, 100, "스프링 입문");
        data.lecture(2, 100, "파이썬 기초");
        data.lecture(3, 200, "자바 기초");
        handle(docs("3"));
        jdbcTemplate.update("INSERT INTO chatbot.index_task (index_type, doc_id, action, next_attempt_at, created_at, done_at)"
                + " VALUES ('LECTURE', '3', 'UPSERT', now(), now(), now())");
        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET title = '자바 기초 개정판' WHERE id = 3");

        assertThat(reconciler.check()).isEqualTo(3);
        // 실행기가 아직 처리하지 않았어도(첫 전체 색인이 길어짐, pod 재기동) 다음 점검은 같은 문서를 또 적지 않는다
        assertThat(reconciler.check()).isZero();
        assertThat(pendingDocs()).containsExactlyInAnyOrder("1", "2", "3");
    }

    private LectureSnapshot lecture(long id) {
        return store.lectures(List.of(id)).get(id);
    }

    // ids 검색은 refresh 뒤에 보이므로 처리 뒤 바로 refresh한다(운영 실행기는 refresh를 기다리지 않는다)
    private Map<String, RuntimeException> handle(SequencedMap<String, IndexAction> docs) {
        Map<String, RuntimeException> failures = handler.handle(docs);
        index.refresh(IndexType.LECTURE);
        return failures;
    }

    private List<String> pendingDocs() {
        return jdbcTemplate.queryForList("SELECT doc_id FROM chatbot.index_task WHERE done_at IS NULL AND index_type = 'LECTURE'",
                String.class);
    }

    private static SequencedMap<String, IndexAction> docs(String... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (String id : ids) {
            docs.put(id, IndexAction.UPSERT);
        }
        return docs;
    }

    private static void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-lectures-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
