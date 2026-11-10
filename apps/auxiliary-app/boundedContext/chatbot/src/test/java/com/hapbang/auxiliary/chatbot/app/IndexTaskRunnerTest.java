package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 색인 요청 실행기를 실제 DB로 확인한다(#718 공통 기반). 처리기는 테스트가 만든 가짜다 */
class IndexTaskRunnerTest extends ChatbotIntegrationTestBase {

    private static final ChatbotIndexProperties.Task SETTINGS = new ChatbotIndexProperties.Task(Duration.ofSeconds(5), 50,
            Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofHours(1), 3, Duration.ofDays(7));

    @Autowired
    private IndexTaskStore store;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FakeHandler lectures;
    private FakeHandler influencers;

    @BeforeEach
    void setUp() {
        lectures = new FakeHandler(IndexType.LECTURE);
        influencers = new FakeHandler(IndexType.INFLUENCER);
    }

    @Test
    @DisplayName("같은 문서에 쌓인 요청은 처리기를 한 번만 부르고(마지막 요청 종류로) 모두 끝낸다")
    void coalescesSameDocument() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "5", IndexAction.DELETE, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());

        assertThat(runner(lectures).runOnce()).isEqualTo(2);

        assertThat(lectures.calls).containsExactly("5:DELETE", "6:UPSERT");
        assertThat(pending()).isZero();
        assertThat(counter("LECTURE", "done")).isEqualTo(2);
    }

    @Test
    @DisplayName("처리기가 없거나 쓸 수 없는 종류는 가져가지 않고 남겨 둔다")
    void skipsTypesWithoutAvailableHandler() {
        store.add(IndexType.INFLUENCER, "7", IndexAction.DELETE, now());
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        lectures.available = false;

        assertThat(runner(lectures).runOnce()).isZero();
        assertThat(pending()).isEqualTo(2);
        assertThat(attempts("7")).isZero();

        lectures.available = true;
        assertThat(runner(lectures).runOnce()).isEqualTo(1);
        assertThat(lectures.calls).containsExactly("5:UPSERT");
        // 인플루언서 처리기(#719)가 생기면 그때 처리한다
        assertThat(runner(lectures, influencers).runOnce()).isEqualTo(1);
        assertThat(influencers.calls).containsExactly("7:DELETE");
    }

    @Test
    @DisplayName("실패하면 원인을 남기고 30초 → 60초로 늘려 다시 시도한다. 그 전에는 다시 가져가지 않는다")
    void retriesWithBackoff() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        lectures.failures = 2;
        IndexTaskRunner runner = runner(lectures);

        runner.runOnce();
        assertThat(row("5")).containsEntry("attempts", 1).containsEntry("done_at", null)
                .containsEntry("next_attempt_at", now().plusSeconds(30))
                .hasEntrySatisfying("last_error", error -> assertThat((String) error).startsWith("IllegalStateException: 가짜 실패"));
        assertThat(counter("LECTURE", "failed")).isEqualTo(1);
        assertThat(runner.runOnce()).isZero();

        clock.advance(Duration.ofSeconds(30));
        runner.runOnce();
        assertThat(row("5")).containsEntry("attempts", 2).containsEntry("next_attempt_at", now().plusSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        runner.runOnce();
        assertThat(row("5")).containsEntry("attempts", 3).containsEntry("last_error", null)
                .containsEntry("done_at", now());
        assertThat(lectures.calls).hasSize(3);
    }

    @Test
    @DisplayName("다시 시도 간격은 두 배씩 늘고 상한(1시간)에서 멈춘다")
    void backoffIsCapped() {
        IndexTaskRunner runner = runner(lectures);

        assertThat(runner.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(runner.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(runner.backoff(7)).isEqualTo(Duration.ofMinutes(32));
        assertThat(runner.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(runner.backoff(100)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("가져간 요청은 임대가 끝나기 전에는 다른 실행이 가져가지 않고, 임대가 끝나면(실행 중 pod가 죽음) 다시 가져간다")
    void leasePreventsDoubleClaim() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());

        List<IndexTaskStore.IndexTask> first = store.claim(List.of(IndexType.LECTURE), 10, now(), now().plusMinutes(5));
        List<IndexTaskStore.IndexTask> second = store.claim(List.of(IndexType.LECTURE), 10, now(), now().plusMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        List<IndexTaskStore.IndexTask> afterLease = store.claim(List.of(IndexType.LECTURE), 10, now(), now().plusMinutes(5));

        assertThat(first).singleElement().extracting(IndexTaskStore.IndexTask::attempts).isEqualTo(1);
        assertThat(second).isEmpty();
        assertThat(afterLease).singleElement().extracting(IndexTaskStore.IndexTask::attempts).isEqualTo(2);
    }

    @Test
    @DisplayName("보관 기간(7일)이 지난 끝난 요청만 지운다")
    void purgesOldDoneTasks() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());
        IndexTaskRunner runner = runner(lectures);
        runner.runOnce();
        store.add(IndexType.LECTURE, "7", IndexAction.UPSERT, now().plusDays(10));

        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertThat(runner.purgeDone()).isEqualTo(2);
        assertThat(rows.indexTasks()).singleElement().satisfies(task -> assertThat(task).containsEntry("doc_id", "7"));
    }

    @Test
    @DisplayName("한 종류에 처리기가 둘이면 시작할 때 실패한다")
    void rejectsDuplicateHandlers() {
        assertThatThrownBy(() -> runner(lectures, new FakeHandler(IndexType.LECTURE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LECTURE");
    }

    private IndexTaskRunner runner(IndexTaskHandler... handlers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        ChatbotIndexProperties properties = new ChatbotIndexProperties("chatbot-lectures-test-8",
                "chatbot-influencers-test-8", 8, "", "", SETTINGS);
        return new IndexTaskRunner(store, List.of(handlers), properties, beans.getBeanProvider(MeterRegistry.class),
                clockBean);
    }

    private LocalDateTime now() {
        return CopyTimes.now(clockBean);
    }

    private int pending() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM chatbot.index_task WHERE done_at IS NULL", Integer.class);
    }

    private int attempts(String docId) {
        return jdbcTemplate.queryForObject("SELECT max(attempts) FROM chatbot.index_task WHERE doc_id = ?", Integer.class,
                docId);
    }

    private Map<String, Object> row(String docId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT attempts, next_attempt_at, done_at, last_error FROM chatbot.index_task WHERE doc_id = ?", docId);
        row.replaceAll((key, value) -> value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : value);
        return row;
    }

    private double counter(String type, String result) {
        return registry.get(IndexTaskRunner.METRIC).tag("type", type).tag("result", result).counter().count();
    }

    private static final class FakeHandler implements IndexTaskHandler {

        private final IndexType type;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private volatile boolean available = true;
        private volatile int failures;

        private FakeHandler(IndexType type) {
            this.type = type;
        }

        @Override
        public IndexType type() {
            return type;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void handle(String docId, IndexAction action) {
            calls.add(docId + ":" + action);
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("가짜 실패");
            }
        }
    }
}
