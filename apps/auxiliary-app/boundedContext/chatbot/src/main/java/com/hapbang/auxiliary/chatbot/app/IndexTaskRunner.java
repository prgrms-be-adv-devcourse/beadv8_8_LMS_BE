package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.config.ChatbotConfig;
import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore.IndexTask;

import lombok.extern.slf4j.Slf4j;

/**
 * 색인 요청({@code chatbot.index_task}) 실행기(#718 공통 기반). 사본이 바뀐 트랜잭션에 적힌 요청을 커밋 뒤 가져가 처리기에 넘긴다.
 * <ul>
 *     <li>처리기({@link IndexTaskHandler})가 있고 지금 쓸 수 있는 종류만 가져간다. 없는 종류(예: #719 전의 인플루언서 삭제 요청)는
 *     남아 있다가 처리기가 생기면 처리된다.</li>
 *     <li>문서 단위로 가져간다({@link IndexTaskStore#claim}). 가져가기는 짧은 트랜잭션에서 advisory lock
 *     ({@link ChatbotJob#INDEX_TASK_CLAIM})으로 여러 pod를 한 줄로 세운다. 한 문서는 한 실행만 처리하므로 옛 상태가 새 상태를 덮지 않는다.</li>
 *     <li>종류마다 처리기를 한 번 부르고 그 종류의 문서를 모두 넘긴다. 성공한 문서의 요청은 끝내고, 실패한 문서는 {@code retry-initial}부터
 *     두 배씩 {@code retry-max}까지 늘려 다시 시도한다. 실패 로그는 원인(예외 객체)마다 한 줄이다: 문서 하나만 실패한 원인은 문서별 WARN,
 *     처리기 전체 실패처럼 여러 문서가 같은 원인이면 문서 수와 예시 문서 ID를 담은 WARN 한 줄. 시도 횟수가
 *     {@code alert-after-attempts} 이상인 문서가 있으면 회차당 한 번 {@code [CHATBOT_INDEX_TASK_STUCK]} ERROR(건수·예시 문서 ID)로 알린다.</li>
 *     <li>회차가 임대의 절반을 넘기면 아직 처리기에 넘기지 않은 종류의 요청은 돌려준다(임대가 끝나 다른 pod가 같은 문서를 가져가지 않게).</li>
 *     <li>끝내기·다시 시도 기록(DB)이 실패하면 예외가 배치로 나간다. 요청은 임대 끝까지 남아 다시 처리된다(처리기는 여러 번 불러도 결과가 같다).</li>
 * </ul>
 * 지표: {@code hapbang.chatbot.index.tasks{type, result=done|failed}} 처리한 문서 수.
 */
@Slf4j
@Service
public class IndexTaskRunner {

    /** 묶어 남기는 로그에 적는 예시 문서 ID 수 */
    private static final int EXAMPLE_DOCS = 5;

    private final IndexTaskStore store;
    private final Map<IndexType, IndexTaskHandler> handlers;
    private final ChatbotIndexProperties.Task settings;
    private final TransactionTemplate transaction;
    private final ChatbotMetrics metrics;
    private final Clock clock;

    IndexTaskRunner(IndexTaskStore store, List<IndexTaskHandler> handlers, ChatbotIndexProperties properties,
            PlatformTransactionManager transactionManager, ChatbotMetrics metrics,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.store = store;
        this.handlers = byType(handlers);
        this.settings = properties.task();
        this.transaction = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 할 일을 한 번 가져가 처리한다.
     *
     * @return 처리기에 넘긴 문서 수(성공 + 실패)
     */
    public int runOnce() {
        Set<IndexType> types = handlers.values().stream()
                .filter(IndexTaskHandler::available)
                .map(IndexTaskHandler::type)
                .collect(Collectors.toSet());
        if (types.isEmpty()) {
            return 0;
        }
        Instant start = clock.instant();
        LocalDateTime now = now();
        LocalDateTime leaseUntil = now.plus(settings.lease());
        List<IndexTask> tasks = transaction.execute(status -> {
            store.lockClaims(ChatbotJob.INDEX_TASK_CLAIM.lockKey());
            return store.claim(types, settings.batchSize(), now, leaseUntil);
        });
        Map<IndexType, SequencedMap<String, List<IndexTask>>> byType = new EnumMap<>(IndexType.class);
        for (IndexTask task : tasks) {
            byType.computeIfAbsent(task.type(), type -> new LinkedHashMap<>())
                    .computeIfAbsent(task.docId(), docId -> new ArrayList<>()).add(task);
        }
        Tally tally = new Tally();
        boolean first = true;
        try {
            for (Map.Entry<IndexType, SequencedMap<String, List<IndexTask>>> entry : byType.entrySet()) {
                List<Long> ids = entry.getValue().values().stream().flatMap(List::stream).map(IndexTask::id).toList();
                Duration elapsed = Duration.between(start, clock.instant());
                if (!first && elapsed.compareTo(settings.lease().dividedBy(2)) > 0) {
                    store.release(ids, now(), leaseUntil);
                    log.info("회차가 임대 시간의 절반을 넘겨 남은 색인 요청을 돌려준다 type={}, docs={}, elapsed={}", entry.getKey(),
                            entry.getValue().size(), elapsed);
                    continue;
                }
                first = false;
                process(entry.getKey(), entry.getValue(), leaseUntil, tally);
            }
        } finally {
            // 원인 하나에 알림이 문서 수만큼 가지 않게 회차당 한 줄. 뒤 종류의 DB 기록이 실패해도 앞에서 센 것은 알린다
            if (tally.stuck > 0) {
                log.error("[CHATBOT_INDEX_TASK_STUCK] 색인 요청이 계속 실패한다 stuck={}, docIds={}, alertAfterAttempts={}",
                        tally.stuck, tally.stuckExamples, settings.alertAfterAttempts(), tally.firstStuckCause);
            }
        }
        if (tally.done + tally.failed > 0) {
            log.info("색인 요청을 처리했다 done={}, failed={}", tally.done, tally.failed);
        }
        return tally.done + tally.failed;
    }

    /** 보관 기간이 지난 끝난 요청을 지운다. @return 지운 행 수 */
    public int purgeDone() {
        int deleted = store.deleteDoneBefore(now().minus(settings.doneRetention()));
        if (deleted > 0) {
            log.info("끝난 색인 요청을 지웠다 deleted={}, retention={}", deleted, settings.doneRetention());
        }
        return deleted;
    }

    private void process(IndexType type, SequencedMap<String, List<IndexTask>> byDoc, LocalDateTime leaseUntil, Tally tally) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        byDoc.forEach((docId, tasks) -> docs.put(docId, tasks.getLast().action()));
        Map<String, RuntimeException> failures;
        try {
            failures = handlers.get(type).handle(docs);
        } catch (RuntimeException e) {
            failures = new LinkedHashMap<>();
            for (String docId : docs.keySet()) {
                failures.put(docId, e);
            }
        }
        List<Long> doneIds = new ArrayList<>();
        int succeeded = 0;
        // 같은 예외 객체로 실패한 문서(처리기 전체 실패, 임베딩 한 번의 실패)는 원인이 하나라 한 줄로 남긴다
        Map<RuntimeException, List<Retry>> byCause = new IdentityHashMap<>();
        for (Map.Entry<String, List<IndexTask>> doc : byDoc.entrySet()) {
            RuntimeException failure = failures.get(doc.getKey());
            if (failure == null) {
                doc.getValue().forEach(task -> doneIds.add(task.id()));
                succeeded++;
                continue;
            }
            Retry retry = retryLater(type, doc.getKey(), doc.getValue(), failure, leaseUntil);
            byCause.computeIfAbsent(failure, cause -> new ArrayList<>()).add(retry);
            if (retry.attempts() >= settings.alertAfterAttempts()) {
                tally.stuck(type, doc.getKey(), failure);
            }
            tally.failed++;
        }
        logFailures(type, byCause);
        store.done(doneIds, now(), leaseUntil);
        // 끝내기 기록이 성공한 뒤에 센다. DB 오류로 다시 처리되면 한 번만 센다
        metrics.indexTasks(type, Result.DONE, succeeded);
        tally.done += succeeded;
    }

    private Retry retryLater(IndexType type, String docId, List<IndexTask> tasks, RuntimeException failure,
            LocalDateTime leaseUntil) {
        int attempts = tasks.stream().mapToInt(IndexTask::attempts).max().orElse(1);
        LocalDateTime next = now().plus(backoff(attempts));
        store.retryLater(tasks.stream().map(IndexTask::id).toList(), next,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(), leaseUntil);
        metrics.indexTasks(type, Result.FAILED, 1);
        return new Retry(docId, attempts, next);
    }

    // 문서 하나만 실패한 원인은 문서별로(시도 횟수·다음 시각), 여러 문서가 같은 원인이면 한 줄(문서 수·예시 문서 ID)로 남긴다
    private static void logFailures(IndexType type, Map<RuntimeException, List<Retry>> byCause) {
        for (Map.Entry<RuntimeException, List<Retry>> entry : byCause.entrySet()) {
            List<Retry> retries = entry.getValue();
            if (retries.size() == 1) {
                Retry retry = retries.getFirst();
                log.warn("색인 요청이 실패해 나중에 다시 한다 type={}, docId={}, attempts={}, next={}", type, retry.docId(),
                        retry.attempts(), retry.next(), entry.getKey());
                continue;
            }
            List<String> examples = new ArrayList<>();
            for (Retry retry : retries.subList(0, Math.min(retries.size(), EXAMPLE_DOCS))) {
                examples.add(retry.docId());
            }
            log.warn("색인 요청 여러 건이 같은 원인으로 실패해 나중에 다시 한다 type={}, docs={}, docIds={}", type, retries.size(),
                    examples, entry.getKey());
        }
    }

    // 시도 횟수 n번째 실패 뒤 기다릴 시간: retry-initial × 2^(n-1), 상한 retry-max
    Duration backoff(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 30);
        Duration delay = settings.retryInitial().multipliedBy(1L << doublings);
        return delay.compareTo(settings.retryMax()) > 0 ? settings.retryMax() : delay;
    }

    private LocalDateTime now() {
        return CopyTimes.now(clock);
    }

    private static Map<IndexType, IndexTaskHandler> byType(List<IndexTaskHandler> handlers) {
        Map<IndexType, IndexTaskHandler> map = new EnumMap<>(IndexType.class);
        for (IndexTaskHandler handler : handlers) {
            IndexTaskHandler previous = map.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("색인 요청 처리기가 한 종류에 둘 있습니다 type=" + handler.type() + ", "
                        + previous.getClass().getSimpleName() + ", " + handler.getClass().getSimpleName());
            }
        }
        return map;
    }

    /** 지표 {@value ChatbotMetrics#INDEX_TASKS}의 {@code result} 태그 */
    enum Result {
        DONE("색인 요청을 끝냄"),
        FAILED("색인 요청이 실패해 다시 시도함");

        private final String description;

        Result(String description) {
            this.description = description;
        }

        String getDescription() {
            return description;
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Retry(String docId, int attempts, LocalDateTime next) {
    }

    private static final class Tally {
        private int done;
        private int failed;
        private final List<String> stuckExamples = new ArrayList<>();
        private int stuck;
        private RuntimeException firstStuckCause;

        private void stuck(IndexType type, String docId, RuntimeException cause) {
            stuck++;
            if (stuckExamples.size() < EXAMPLE_DOCS) {
                stuckExamples.add(type + ":" + docId);
            }
            if (firstStuckCause == null) {
                firstStuckCause = cause;
            }
        }
    }
}
