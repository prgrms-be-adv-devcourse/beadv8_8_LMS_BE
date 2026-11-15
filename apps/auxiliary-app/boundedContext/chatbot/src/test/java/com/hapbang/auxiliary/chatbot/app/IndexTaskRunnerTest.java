package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore.IndexTask;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 색인 요청 실행기를 실제 DB로 확인한다(#718 공통 기반). 처리기는 테스트가 만든 가짜다 */
class IndexTaskRunnerTest extends ChatbotIntegrationTestBase {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private IndexTaskStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FakeHandler lectures;
    private FakeHandler influencers;

    @BeforeEach
    void setUp() {
        lectures = new FakeHandler(IndexType.LECTURE);
        influencers = new FakeHandler(IndexType.INFLUENCER);
    }

    @Test
    @DisplayName("한 종류의 문서를 처리기에 한 번에 넘긴다. 같은 문서에 쌓인 요청은 한 문서로(마지막 요청 종류), 모두 끝낸다")
    void coalescesSameDocument() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "5", IndexAction.DELETE, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());

        assertThat(runner(LEASE, lectures).runOnce()).isEqualTo(2);

        assertThat(lectures.calls).containsExactly(Map.of("5", IndexAction.DELETE, "6", IndexAction.UPSERT));
        assertThat(pending()).isZero();
        assertThat(counter("LECTURE", "done")).isEqualTo(2);
    }

    @Test
    @DisplayName("처리기가 없거나 쓸 수 없는 종류는 가져가지 않고 남겨 둔다")
    void skipsTypesWithoutAvailableHandler() {
        store.add(IndexType.INFLUENCER, "7", IndexAction.DELETE, now());
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        lectures.available = false;

        assertThat(runner(LEASE, lectures).runOnce()).isZero();
        assertThat(pending()).isEqualTo(2);
        assertThat(attempts("7")).isZero();

        lectures.available = true;
        assertThat(runner(LEASE, lectures).runOnce()).isEqualTo(1);
        assertThat(lectures.calls).containsExactly(Map.of("5", IndexAction.UPSERT));
        // 인플루언서 처리기(#719)가 생기면 그때 처리한다
        assertThat(runner(LEASE, lectures, influencers).runOnce()).isEqualTo(1);
        assertThat(influencers.calls).containsExactly(Map.of("7", IndexAction.DELETE));
    }

    @Test
    @DisplayName("실패한 문서만 원인을 남기고 30초 → 60초로 늘려 다시 시도한다. 성공한 문서는 끝낸다. 그 전에는 다시 가져가지 않는다")
    void retriesFailedDocumentsWithBackoff() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());
        lectures.failures.put("5", 2);
        IndexTaskRunner runner = runner(LEASE, lectures);

        runner.runOnce();
        assertThat(row("6")).doesNotContainEntry("done_at", null);
        assertThat(row("5")).containsEntry("attempts", 1).containsEntry("done_at", null)
                .containsEntry("next_attempt_at", now().plusSeconds(30))
                .hasEntrySatisfying("last_error", error -> assertThat((String) error).startsWith("IllegalStateException: 가짜 실패"));
        assertThat(counter("LECTURE", "failed")).isEqualTo(1);
        assertThat(counter("LECTURE", "done")).isEqualTo(1);
        assertThat(runner.runOnce()).isZero();

        clock.advance(Duration.ofSeconds(30));
        runner.runOnce();
        assertThat(row("5")).containsEntry("attempts", 2).containsEntry("next_attempt_at", now().plusSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        runner.runOnce();
        assertThat(row("5")).containsEntry("attempts", 3).containsEntry("last_error", null).containsEntry("done_at", now());
        assertThat(lectures.calls).hasSize(3);
    }

    @Test
    @DisplayName("처리기 전체가 예외를 던지면 넘긴 문서가 모두 실패로 간다")
    void handlerExceptionFailsAllDocuments() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());
        lectures.throwAll = true;

        assertThat(runner(LEASE, lectures).runOnce()).isEqualTo(2);

        assertThat(counter("LECTURE", "failed")).isEqualTo(2);
        assertThat(row("5")).containsEntry("next_attempt_at", now().plusSeconds(30));
        assertThat(row("6")).containsEntry("next_attempt_at", now().plusSeconds(30));
    }

    @Test
    @DisplayName("다시 시도 간격은 두 배씩 늘고 상한(1시간)에서 멈춘다")
    void backoffIsCapped() {
        IndexTaskRunner runner = runner(LEASE, lectures);

        assertThat(runner.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(runner.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(runner.backoff(7)).isEqualTo(Duration.ofMinutes(32));
        assertThat(runner.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(runner.backoff(100)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("문서 단위로 가져간다: 임대 중인 문서는 새 요청이 와도 가져가지 않고, 끝난 뒤 새 요청을 가져간다")
    void claimsWholeDocuments() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        List<IndexTask> first = claim(now().plus(LEASE));
        // 처리하는 동안 같은 문서에 새 요청이 들어온다
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());

        assertThat(claim(now().plus(LEASE))).isEmpty();

        store.done(first.stream().map(IndexTask::id).toList(), now(), now().plus(LEASE));
        assertThat(claim(now().plus(LEASE))).singleElement().extracting(IndexTask::attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("두 실행(pod)이 동시에 가져가면 뒤 실행은 앞 가져가기 트랜잭션이 끝날 때까지 advisory lock에서 기다리고, 그 문서를 가져가지 않는다")
    void concurrentClaimsAreSerialized() throws Exception {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());

        List<List<IndexTask>> results = claimConcurrently(true, "advisory");

        assertThat(results.get(0)).hasSize(1);
        assertThat(results.get(1)).isEmpty();
    }

    @Test
    @DisplayName("실행기(runOnce)는 가져가기 전에 advisory lock을 잡는다: 다른 실행이 락을 쥐고 있으면 기다렸다가 풀린 뒤 처리한다")
    void runOnceTakesClaimLock() throws Exception {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        IndexTaskRunner runner = runner(LEASE, lectures);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 다른 pod의 가져가기 트랜잭션이 락을 쥐고 있다
            Future<?> holder = pool.submit(() -> transaction.executeWithoutResult(status -> {
                store.lockClaims(ChatbotJob.INDEX_TASK_CLAIM.lockKey());
                locked.countDown();
                awaitLatch(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> run = pool.submit(runner::runOnce);

            await().atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class) > 0);
            assertThat(run.isDone()).isFalse();
            assertThat(lectures.calls).isEmpty();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(run.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(lectures.calls).containsExactly(Map.of("5", IndexAction.UPSERT));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("이중 방어: 락 없이 겹쳐도 뒤 UPDATE는 앞 실행이 임대한 행을 다시 확인할 때 빼고 가져가지 않는다")
    void updateRecheckSkipsLeasedRows() throws Exception {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());

        List<List<IndexTask>> results = claimConcurrently(false, "transactionid");

        assertThat(results.get(0)).hasSize(1);
        assertThat(results.get(1)).isEmpty();
    }

    @Test
    @DisplayName("임대가 끝나면(실행 중 pod가 죽음) 다시 가져가고, 늦게 끝난 옛 실행의 끝내기는 무시한다")
    void expiredLeaseIsReclaimedAndOldOwnerIgnored() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        LocalDateTime oldLease = now().plus(LEASE);
        List<IndexTask> first = claim(oldLease);

        clock.advance(LEASE);
        LocalDateTime newLease = now().plus(LEASE);
        List<IndexTask> second = claim(newLease);
        assertThat(second).singleElement().extracting(IndexTask::attempts).isEqualTo(2);

        List<Long> ids = first.stream().map(IndexTask::id).toList();
        store.done(ids, now(), oldLease);
        store.retryLater(ids, now().plusHours(1), "늦은 실패", oldLease);
        assertThat(row("5")).containsEntry("done_at", null).containsEntry("next_attempt_at", newLease)
                .containsEntry("last_error", null);

        store.done(ids, now(), newLease);
        assertThat(pending()).isZero();
    }

    @Test
    @DisplayName("회차가 임대 시간의 절반을 넘기면 아직 넘기지 않은 종류의 요청을 돌려준다(시도로 세지 않는다)")
    void releasesRemainingTypesWhenLeaseRunsShort() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.INFLUENCER, "7", IndexAction.DELETE, now());
        // 첫 종류(강의)를 처리하는 데 임대의 절반보다 오래 걸린다
        lectures.sleep = Duration.ofMillis(50);

        assertThat(runner(Duration.ofMillis(20), lectures, influencers).runOnce()).isEqualTo(1);

        assertThat(influencers.calls).isEmpty();
        assertThat(row("7")).containsEntry("attempts", 0).containsEntry("done_at", null).containsEntry("next_attempt_at", now());
        assertThat(runner(LEASE, lectures, influencers).runOnce()).isEqualTo(1);
        assertThat(influencers.calls).containsExactly(Map.of("7", IndexAction.DELETE));
    }

    @Test
    @DisplayName("보관 기간(7일)이 지난 끝난 요청만 지운다")
    void purgesOldDoneTasks() {
        store.add(IndexType.LECTURE, "5", IndexAction.UPSERT, now());
        store.add(IndexType.LECTURE, "6", IndexAction.UPSERT, now());
        IndexTaskRunner runner = runner(LEASE, lectures);
        runner.runOnce();
        store.add(IndexType.LECTURE, "7", IndexAction.UPSERT, now().plusDays(10));

        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertThat(runner.purgeDone()).isEqualTo(2);
        assertThat(rows.indexTasks()).singleElement().satisfies(task -> assertThat(task).containsEntry("doc_id", "7"));
    }

    @Test
    @DisplayName("한 종류에 처리기가 둘이면 시작할 때 실패한다")
    void rejectsDuplicateHandlers() {
        assertThatThrownBy(() -> runner(LEASE, lectures, new FakeHandler(IndexType.LECTURE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LECTURE");
    }

    private IndexTaskRunner runner(Duration lease, IndexTaskHandler... handlers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        ChatbotIndexProperties.Task settings = new ChatbotIndexProperties.Task(Duration.ofSeconds(5), 50, lease,
                Duration.ofSeconds(30), Duration.ofHours(1), 3, Duration.ofDays(7));
        ChatbotIndexProperties properties = new ChatbotIndexProperties("chatbot-lectures-test-8",
                "chatbot-influencers-test-8", 8, "", "", settings);
        return new IndexTaskRunner(store, List.of(handlers), properties, transactionManager,
                beans.getBeanProvider(MeterRegistry.class), clockBean);
    }

    // 앞 실행이 가져간 뒤 트랜잭션을 열어 둔 채로, 뒤 실행이 waitLockType 락을 기다리는 것을 확인하고 앞 트랜잭션을 끝낸다
    private List<List<IndexTask>> claimConcurrently(boolean lock, String waitLockType) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<IndexTask>> first = pool.submit(() -> transaction.execute(status -> {
                if (lock) {
                    store.lockClaims(ChatbotJob.INDEX_TASK_CLAIM.lockKey());
                }
                List<IndexTask> claimed = claim(now().plus(LEASE));
                firstClaimed.countDown();
                awaitLatch(commitFirst);
                return claimed;
            }));
            assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<List<IndexTask>> second = pool.submit(() -> transaction.execute(status -> {
                if (lock) {
                    store.lockClaims(ChatbotJob.INDEX_TASK_CLAIM.lockKey());
                }
                return claim(now().plus(LEASE));
            }));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = ? AND NOT granted", Integer.class, waitLockType) > 0);
            assertThat(second.isDone()).isFalse();
            commitFirst.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            commitFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 앞 트랜잭션을 끝내지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<IndexTask> claim(LocalDateTime leaseUntil) {
        return store.claim(List.of(IndexType.LECTURE), 10, now(), leaseUntil);
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
        private final List<Map<String, IndexAction>> calls = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> failures = new ConcurrentHashMap<>();
        private volatile boolean available = true;
        private volatile boolean throwAll;
        private volatile Duration sleep = Duration.ZERO;

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
        public Map<String, RuntimeException> handle(SequencedMap<String, IndexAction> docs) {
            calls.add(Map.copyOf(docs));
            if (!sleep.isZero()) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwAll) {
                throw new IllegalStateException("처리기 전체 실패");
            }
            Map<String, RuntimeException> failed = new LinkedHashMap<>();
            for (String docId : docs.keySet()) {
                int left = failures.getOrDefault(docId, 0);
                if (left > 0) {
                    failures.put(docId, left - 1);
                    failed.put(docId, new IllegalStateException("가짜 실패"));
                }
            }
            return failed;
        }
    }
}
