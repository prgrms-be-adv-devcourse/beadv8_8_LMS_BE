package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.ChatStreamJobProcessor.Outcome;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatDeliveryReprocessAudit;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.ChatDeliveryReprocessAuditRepository;
import com.hapbang.chat.out.JdbcChatPublishCheckpointStore;

/**
 * 시도·재적재 상한의 격리와 운영 재처리 감사 기록 (설계 4.5 7번, 테스트 기준 "Stream 유실·보존").
 * consumer max-attempts=3, relay max-attempts=2, reconstruct max-requeues=2.
 */
@DirtiesContext
class ChatDeliveryIsolationTest extends ChatDeliveryTestSupport {

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @Autowired
    private ChatDeliveryReprocessAuditRepository auditRepository;

    @Autowired
    private JdbcChatPublishCheckpointStore checkpointStore;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 마지막_항목이_사라진_채_재적재_상한에_닿으면_REQUEUE_EXHAUSTED로_격리하고_운영_재처리로_한_번_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        for (int i = 0; i < 2; i++) {
            loseStreamAndPassCooldown(deliveryId);
            assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isEqualTo(1);
        }
        String lastEntryId = outbox(deliveryId).getStreamEntryId();
        loseStreamAndPassCooldown(deliveryId);

        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();

        ChatPublishCheckpoint dead = checkpoint(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(dead.getDeadReason()).isEqualTo(ChatDeadReason.REQUEUE_EXHAUSTED);
        assertThat(dead.getDeadStreamEntryId()).isEqualTo(lastEntryId);
        assertThat(dead.getJobId()).isEqualTo(deliveryId);
        // 격리한 작업은 자동 재구성 대상이 아니다
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint published = checkpoint(deliveryId);
        assertThat(published.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(published.getGeneration()).isEqualTo(1);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
        ChatOutbox outbox = outbox(deliveryId);
        assertThat(outbox.getRequeueCount()).isEqualTo(1);
        assertThat(outbox.getTotalRequeueCount()).isEqualTo(3);
        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(ChatJobStream.DELIVERY.consumerGroup(),
                deliveryId)).singleElement().satisfies(audit -> {
                    assertThat(audit.getPreviousDeadReason()).isEqualTo("REQUEUE_EXHAUSTED");
                    assertThat(audit.getPreviousRequeueCount()).isEqualTo(2);
                    assertThat(audit.getPreviousStreamEntryId()).isEqualTo(lastEntryId);
                });
    }

    @Test
    void 상한에_닿는_실패는_실패_기록과_격리를_한_번에_남겨_ACK_전에_종료돼도_다시_발행하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(3);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        handleNow(job, deliveryId);
        handleNow(job, deliveryId);
        // 세 번째 실패를 기록한 뒤 ACK 전에 종료
        doThrow(new IllegalStateException("종료")).doCallRealMethod().when(spiedQueue)
                .ack(eq(ChatJobStream.DELIVERY), anyString());

        assertThat(handleNow(job, deliveryId)).isEqualTo(Outcome.NOT_ACKED);

        ChatPublishCheckpoint dead = checkpoint(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(dead.getAttemptCount()).isEqualTo(3);
        assertThat(dead.getDeadReason()).isEqualTo(ChatDeadReason.PUBLISH_RETRY_EXHAUSTED);
        assertThat(dead.getDeadStreamEntryId()).isEqualTo(job.entryId());
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);

        int calls = fanout.publishCalls();
        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.ALREADY_DONE);
        assertThat(fanout.publishCalls()).isEqualTo(calls);
        assertThat(fanout.messages()).isEmpty();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 시도_상한에_닿은_PENDING이_남아_있으면_발행하지_않고_먼저_격리한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        // 상한 실패를 기록한 뒤 격리 전에 종료됐던 옛 상태(또는 상한을 낮춘 설정 변경)
        jdbcClient.sql("""
                        update chats.chat_publish_checkpoint set attempt_count = 3, next_attempt_at = null
                        where job_id = :id
                        """)
                .param("id", deliveryId)
                .update();

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.ISOLATED);

        assertThat(fanout.publishCalls()).isZero();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(checkpoint(deliveryId).getDeadReason()).isEqualTo(ChatDeadReason.PUBLISH_RETRY_EXHAUSTED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 운영_재처리를_두_번_해도_세대별_이력과_누적_횟수가_남는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);

        isolateByPublishFailures(deliveryId);
        String firstDeadEntryId = checkpoint(deliveryId).getDeadStreamEntryId();
        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        assertThat(checkpoint(deliveryId).getLastError()).isNull();

        isolateByPublishFailures(deliveryId);
        String secondDeadEntryId = checkpoint(deliveryId).getDeadStreamEntryId();
        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint checkpoint = checkpoint(deliveryId);
        assertThat(checkpoint.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint.getGeneration()).isEqualTo(2);
        assertThat(checkpoint.getAttemptCount()).isZero();
        assertThat(checkpoint.getTotalAttemptCount()).isEqualTo(6);
        List<ChatDeliveryReprocessAudit> history = auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(
                ChatJobStream.DELIVERY.consumerGroup(), deliveryId);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(ChatDeliveryReprocessAudit::getPreviousGeneration).containsExactly(0, 1);
        assertThat(history).extracting(ChatDeliveryReprocessAudit::getPreviousPublishStatus)
                .containsOnly("DEAD");
        assertThat(history).extracting(ChatDeliveryReprocessAudit::getPreviousPublishAttemptCount)
                .containsExactly(3, 3);
        assertThat(history).extracting(ChatDeliveryReprocessAudit::getPreviousDeadReason)
                .containsOnly("PUBLISH_RETRY_EXHAUSTED");
        assertThat(history).extracting(ChatDeliveryReprocessAudit::getPreviousDeadStreamEntryId)
                .containsExactly(firstDeadEntryId, secondDeadEntryId);
        assertThat(history).allSatisfy(audit -> {
            assertThat(audit.getPreviousPublishError()).contains("주입한 발행 실패");
            assertThat(audit.getJobStream()).isEqualTo("DELIVERY");
            assertThat(audit.getPreviousOutboxStatus()).isEqualTo("ENQUEUED");
        });
    }

    @Test
    void 적재_격리의_운영_재처리도_이력을_남기고_누적_적재_시도를_보존한다() {
        UUID deliveryId = send("안녕하세요");
        isolateOutboxByEnqueueFailures(deliveryId);

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatOutbox outbox = outbox(deliveryId);
        assertThat(outbox.getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getTotalAttemptCount()).isEqualTo(3);
        assertThat(outbox.getLastError()).isNull();
        assertThat(checkpoint(deliveryId).getGeneration()).isEqualTo(1);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(ChatJobStream.DELIVERY.consumerGroup(),
                deliveryId)).singleElement().satisfies(audit -> {
                    assertThat(audit.getPreviousOutboxStatus()).isEqualTo("DEAD");
                    assertThat(audit.getPreviousEnqueueAttemptCount()).isEqualTo(2);
                    assertThat(audit.getPreviousEnqueueError()).contains("Redis 중단");
                    assertThat(audit.getPreviousPublishStatus()).isEqualTo("PENDING");
                    assertThat(audit.getPreviousGeneration()).isZero();
                });
    }

    @Test
    void 격리_상태가_아니면_재처리하지_않고_감사_기록도_남기지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isFalse();

        assertThat(auditRepository.count()).isZero();
        assertThat(checkpoint(deliveryId).getGeneration()).isZero();
    }

    @Test
    void 재처리는_두_행을_잠근_뒤_스냅숏을_남겨_그_사이_격리된_체크포인트의_원인을_잃지_않는다() throws Exception {
        UUID deliveryId = send("안녕하세요");
        isolateOutboxByEnqueueFailures(deliveryId);
        String group = ChatJobStream.DELIVERY.consumerGroup();
        CountDownLatch deadWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 다른 트랜잭션이 체크포인트를 격리하고 아직 커밋하지 않았다
            Future<?> isolating = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                assertThat(checkpointStore.markDead(group, deliveryId, 0, ChatDeadReason.SOURCE_MISSING,
                        "1-0", "동시 격리", ChatDeliveryClock.now())).isTrue();
                deadWritten.countDown();
                await(release);
            }));
            assertThat(deadWritten.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> reprocessing = executor.submit(
                    () -> reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId));
            // 재처리는 체크포인트 행 잠금을 기다린다
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> lockWaiters() > 0 && !reprocessing.isDone());
            release.countDown();
            isolating.get(5, TimeUnit.SECONDS);

            assertThat(reprocessing.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(group, deliveryId)).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getPreviousOutboxStatus()).isEqualTo("DEAD");
                    assertThat(audit.getPreviousPublishStatus()).isEqualTo("DEAD");
                    assertThat(audit.getPreviousDeadReason()).isEqualTo("SOURCE_MISSING");
                    assertThat(audit.getPreviousDeadStreamEntryId()).isEqualTo("1-0");
                });
        ChatPublishCheckpoint reopened = checkpoint(deliveryId);
        assertThat(reopened.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(reopened.getGeneration()).isEqualTo(1);
        assertThat(reopened.getDeadReason()).isNull();
    }

    @Test
    void 다음_시도_시각_없이도_실패를_기록한다() {
        UUID deliveryId = send("안녕하세요");

        var result = checkpointStore.recordFailureOrIsolate(ChatJobStream.DELIVERY.consumerGroup(), deliveryId, 0,
                "실패", null, null, 3, "1-0", ChatDeliveryClock.now());

        assertThat(result).hasValueSatisfying(failure -> {
            assertThat(failure.attemptCount()).isEqualTo(1);
            assertThat(failure.status()).isEqualTo(ChatPublishStatus.PENDING);
        });
        assertThat(checkpoint(deliveryId).getNextAttemptAt()).isNull();
    }

    private void isolateOutboxByEnqueueFailures(UUID deliveryId) {
        doThrow(new IllegalStateException("Redis 중단")).when(spiedQueue).enqueue(eq(ChatJobStream.DELIVERY),
                anyMap());
        relay.relayBatch(ChatJobStream.DELIVERY);
        dueNow(deliveryId);
        relay.relayBatch(ChatJobStream.DELIVERY);
        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.DEAD);
        reset(spiedQueue);
    }

    private long lockWaiters() {
        return jdbcClient.sql("""
                        select count(*) from pg_stat_activity
                        where datname = current_database() and wait_event_type = 'Lock'
                        """)
                .query(Long.class)
                .single();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 현재 세대에서 발행을 시도 상한(3회)까지 실패시켜 격리한다 */
    private void isolateByPublishFailures(UUID deliveryId) {
        fanout.failNext(3);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        for (int i = 0; i < 3; i++) {
            handleNow(job, deliveryId);
        }
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
    }

    private void loseStreamAndPassCooldown(UUID deliveryId) {
        redis.delete(keys.deliveryStream());
        jdbcClient.sql("update chats.chat_outbox set last_requeued_at = null where delivery_id = :id")
                .param("id", deliveryId)
                .update();
    }

    private void dueNow(UUID deliveryId) {
        jdbcClient.sql("update chats.chat_outbox set next_attempt_at = :past where delivery_id = :id")
                .param("past", ChatDeliveryClock.now().minusSeconds(1))
                .param("id", deliveryId)
                .update();
    }
}
