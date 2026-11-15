package com.hapbang.auxiliary.notification.app;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka 소비에서 나온 알림 저장 결과를 센다(ND-160). 지표 {@value #OUTCOMES_METRIC}{topic, result}.
 * <ul>
 *     <li>소비 문맥({@link #enter})이 있는 스레드의 저장만 센다. 소비자의 레코드 처리 앞뒤로 들어가고 나온다. 예약 배치의 저장은 문맥이
 *     없어 세지 않는다(예약은 ND-150 지표가 센다).</li>
 *     <li>트랜잭션 안이면 commit된 뒤에만 센다. 롤백된 시도는 세지 않고, 재시도해 commit되면 그때 한 번 센다. 실패는
 *     {@code consume.failures}가 센다.</li>
 *     <li>결과는 commit된 저장 시도의 결과 건수다. 알림 행 수와 항상 같지는 않다(UPDATED는 행을 늘리지 않고 DUPLICATE·SKIPPED_RECEIVER는
 *     행이 없다).</li>
 *     <li>commit 뒤 집계가 실패해도 예외를 밖으로 내지 않는다. 이미 commit된 처리가 실패로 보여 다시 처리되면 안 된다.</li>
 * </ul>
 */
@Slf4j
@Component
public class NotificationOutcomeRecorder {

    public static final String OUTCOMES_METRIC = "hapbang.notification.consume.outcomes";

    private static final ThreadLocal<String> TOPIC = new ThreadLocal<>();

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public NotificationOutcomeRecorder(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 이 스레드가 {@code topic}의 레코드를 처리하기 시작한다 */
    public void enter(String topic) {
        TOPIC.set(topic);
    }

    /** 이 스레드의 레코드 처리가 끝났다 */
    public void exit() {
        TOPIC.remove();
    }

    /** 저장 결과를 센다. 소비 문맥이 없으면 아무것도 하지 않는다 */
    public void record(NotificationWriteResult result) {
        String topic = TOPIC.get();
        if (topic == null || result == null) {
            return;
        }
        NotificationWriteResult.Outcome outcome = result.outcome();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    countSafely(topic, outcome);
                }
            });
        } else {
            countSafely(topic, outcome);
        }
    }

    /** 결과 하나를 지표에 더한다 */
    public void count(String topic, NotificationWriteResult.Outcome outcome) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(OUTCOMES_METRIC, "topic", topic, "result", outcome.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }

    private void countSafely(String topic, NotificationWriteResult.Outcome outcome) {
        try {
            count(topic, outcome);
        } catch (RuntimeException exception) {
            log.warn("알림 저장 결과 지표를 더하지 못했다(처리는 commit됨) topic={}, result={}", topic, outcome, exception);
        }
    }
}
