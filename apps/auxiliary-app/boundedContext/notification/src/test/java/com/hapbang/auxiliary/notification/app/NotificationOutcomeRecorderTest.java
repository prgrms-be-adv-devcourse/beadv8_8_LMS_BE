package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 소비 결과 집계(ND-160) 단위 시험. test-spec NT-OBS-04(일부)·05(일부).
 * <ul>
 *     <li>Kafka 소비 중(토픽 문맥이 있을 때)의 저장 결과만 센다. 예약 배치처럼 문맥이 없으면 세지 않는다.</li>
 *     <li>트랜잭션 안이면 commit된 뒤에만 센다. 롤백되면 세지 않는다.</li>
 *     <li>commit 뒤 집계가 실패해도 예외를 밖으로 내지 않는다(이미 commit된 처리를 다시 하지 않게).</li>
 * </ul>
 */
class NotificationOutcomeRecorderTest {

    private static final String TOPIC = "billing.events.v1";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationOutcomeRecorder recorder = new NotificationOutcomeRecorder(provider(registry));

    @AfterEach
    void tearDown() {
        recorder.exit();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 소비_문맥이_없으면_세지_않는다() {
        recorder.record(NotificationWriteResult.created(1L));

        assertThat(registry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).counter()).isNull();
    }

    @Test
    void 트랜잭션_밖의_소비_문맥이면_바로_결과별로_센다() {
        recorder.enter(TOPIC);

        recorder.record(NotificationWriteResult.created(1L));
        recorder.record(NotificationWriteResult.of(NotificationWriteResult.Outcome.UPDATED));
        recorder.record(NotificationWriteResult.of(NotificationWriteResult.Outcome.DUPLICATE));
        recorder.record(NotificationWriteResult.of(NotificationWriteResult.Outcome.SKIPPED_RECEIVER));

        assertThat(count("created")).isEqualTo(1.0);
        assertThat(count("updated")).isEqualTo(1.0);
        assertThat(count("duplicate")).isEqualTo(1.0);
        assertThat(count("skipped_receiver")).isEqualTo(1.0);
    }

    @Test
    void 트랜잭션_안이면_commit된_뒤에만_센다() {
        recorder.enter(TOPIC);
        TransactionSynchronizationManager.initSynchronization();

        recorder.record(NotificationWriteResult.created(1L));
        assertThat(count("created")).isZero();

        complete(true);
        assertThat(count("created")).isEqualTo(1.0);
    }

    @Test
    void 롤백되면_세지_않는다() {
        recorder.enter(TOPIC);
        TransactionSynchronizationManager.initSynchronization();

        recorder.record(NotificationWriteResult.created(1L));
        complete(false);

        assertThat(count("created")).isZero();
    }

    @Test
    void commit_뒤_집계가_실패해도_예외를_밖으로_내지_않는다() {
        MeterRegistry broken = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("지표 저장소 고장(시험)");
            }
        };
        NotificationOutcomeRecorder brokenRecorder = new NotificationOutcomeRecorder(provider(broken));
        brokenRecorder.enter(TOPIC);
        TransactionSynchronizationManager.initSynchronization();
        try {
            brokenRecorder.record(NotificationWriteResult.created(1L));

            assertThatCode(() -> complete(true)).doesNotThrowAnyException();
        } finally {
            brokenRecorder.exit();
        }
    }

    @Test
    void 문맥을_나가면_다시_세지_않는다() {
        recorder.enter(TOPIC);
        recorder.exit();

        recorder.record(NotificationWriteResult.created(1L));

        assertThat(registry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).counter()).isNull();
    }

    /** 트랜잭션 매니저가 하는 순서대로 동기화를 부른다: commit이면 afterCommit, 그다음 afterCompletion */
    private static void complete(boolean committed) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (committed) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        int status = committed ? TransactionSynchronization.STATUS_COMMITTED
                : TransactionSynchronization.STATUS_ROLLED_BACK;
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
    }

    private double count(String result) {
        Counter counter = registry.find(NotificationOutcomeRecorder.OUTCOMES_METRIC).tag("topic", TOPIC)
                .tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        return new StaticListableBeanFactory(Map.of("meterRegistry", registry)).getBeanProvider(MeterRegistry.class);
    }
}
