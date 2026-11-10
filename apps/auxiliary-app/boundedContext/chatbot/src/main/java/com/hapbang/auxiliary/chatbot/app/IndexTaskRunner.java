package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore.IndexTask;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 색인 요청({@code chatbot.index_task}) 실행기(#718 공통 기반). 사본이 바뀐 트랜잭션에 적힌 요청을 커밋 뒤 가져가 처리기에 넘긴다.
 * <ul>
 *     <li>처리기({@link IndexTaskHandler})가 있고 지금 쓸 수 있는 종류만 가져간다. 없는 종류(예: #719 전의 인플루언서 삭제 요청)는
 *     남아 있다가 처리기가 생기면 처리된다.</li>
 *     <li>가져간 요청을 문서별로 묶어 처리기를 한 번만 부른다(처리기는 지금 사본 상태로 맞춘다). 성공하면 묶인 요청을 모두 끝낸다.</li>
 *     <li>실패하면 {@code retry-initial}부터 두 배씩, {@code retry-max}까지 늘려 다시 시도한다. 시도 횟수가 {@code alert-after-attempts}
 *     이상이면 {@code [CHATBOT_INDEX_TASK_STUCK]} ERROR로 알린다(그 전은 WARN).</li>
 *     <li>여러 pod가 동시에 돌아도 같은 요청을 함께 가져가지 않는다({@link IndexTaskStore#claim}). 같은 문서의 다른 요청을 두 pod가 따로
 *     가져갈 수는 있는데, 처리기가 둘 다 지금 상태로 맞추므로 결과가 같다.</li>
 * </ul>
 * 지표: {@code hapbang.chatbot.index.tasks{type, result=done|failed}} 처리한 문서 수.
 */
@Slf4j
@Service
public class IndexTaskRunner {

    static final String METRIC = "hapbang.chatbot.index.tasks";

    private final IndexTaskStore store;
    private final Map<IndexType, IndexTaskHandler> handlers;
    private final ChatbotIndexProperties.Task settings;
    private final ObjectProvider<MeterRegistry> registry;
    private final Clock clock;

    IndexTaskRunner(IndexTaskStore store, List<IndexTaskHandler> handlers, ChatbotIndexProperties properties,
            ObjectProvider<MeterRegistry> registry, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.store = store;
        this.handlers = byType(handlers);
        this.settings = properties.task();
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 할 일을 한 번 가져가 처리한다.
     *
     * @return 처리한 문서 수(성공 + 실패)
     */
    public int runOnce() {
        Set<IndexType> types = handlers.values().stream()
                .filter(IndexTaskHandler::available)
                .map(IndexTaskHandler::type)
                .collect(Collectors.toSet());
        LocalDateTime now = now();
        List<IndexTask> tasks = store.claim(types, settings.batchSize(), now, now.plus(settings.lease()));
        Map<DocKey, List<IndexTask>> byDoc = new LinkedHashMap<>();
        for (IndexTask task : tasks) {
            byDoc.computeIfAbsent(new DocKey(task.type(), task.docId()), key -> new ArrayList<>()).add(task);
        }
        byDoc.forEach(this::process);
        return byDoc.size();
    }

    /** 보관 기간이 지난 끝난 요청을 지운다. @return 지운 행 수 */
    public int purgeDone() {
        int deleted = store.deleteDoneBefore(now().minus(settings.doneRetention()));
        if (deleted > 0) {
            log.info("끝난 색인 요청을 지웠다 deleted={}, retention={}", deleted, settings.doneRetention());
        }
        return deleted;
    }

    private void process(DocKey key, List<IndexTask> tasks) {
        IndexTask last = tasks.getLast();
        List<Long> ids = tasks.stream().map(IndexTask::id).toList();
        try {
            handlers.get(key.type()).handle(key.docId(), last.action());
            store.done(ids, now());
            count(key.type(), "done");
        } catch (RuntimeException e) {
            int attempts = tasks.stream().mapToInt(IndexTask::attempts).max().orElse(1);
            LocalDateTime next = now().plus(backoff(attempts));
            store.retryLater(ids, next, e.getClass().getSimpleName() + ": " + e.getMessage());
            count(key.type(), "failed");
            if (attempts >= settings.alertAfterAttempts()) {
                log.error("[CHATBOT_INDEX_TASK_STUCK] 색인 요청이 계속 실패한다 type={}, docId={}, attempts={}, next={}",
                        key.type(), key.docId(), attempts, next, e);
            } else {
                log.warn("색인 요청이 실패해 나중에 다시 한다 type={}, docId={}, attempts={}, next={}", key.type(), key.docId(),
                        attempts, next, e);
            }
        }
    }

    // 시도 횟수 n번째 실패 뒤 기다릴 시간: retry-initial × 2^(n-1), 상한 retry-max
    Duration backoff(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 30);
        Duration delay = settings.retryInitial().multipliedBy(1L << doublings);
        return delay.compareTo(settings.retryMax()) > 0 ? settings.retryMax() : delay;
    }

    private void count(IndexType type, String result) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            Counter.builder(METRIC).description("챗봇 색인 요청을 처리한 문서 수")
                    .tag("type", type.name()).tag("result", result).register(meters).increment();
        }
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

    private record DocKey(IndexType type, String docId) {
    }
}
