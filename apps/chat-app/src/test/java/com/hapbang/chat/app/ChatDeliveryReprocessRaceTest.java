package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.ChatDeliveryReprocessAuditRepository;
import com.hapbang.chat.out.JdbcChatOutboxStore;
import com.hapbang.chat.app.ChatOutboxStore.RequeueCandidate;
import com.hapbang.chat.out.JdbcChatPublishCheckpointStore;

/**
 * 재적재 상한 격리·운영 재처리의 경합과 트랜잭션 경계 (계약 tables-and-migrations "운영 재처리 감사와 누적 횟수",
 * 테스트 기준 "Stream 유실·보존"). consumer max-attempts=3, reconstruct max-requeues=2.
 */
// 새 컨텍스트의 DB 연결 풀이 남아 테스트 DB 연결 수 상한을 넘지 않도록 클래스가 끝나면 닫는다
@DirtiesContext
class ChatDeliveryReprocessRaceTest extends ChatDeliveryTestSupport {

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private JdbcChatOutboxStore spiedOutboxStore;

    @MockitoSpyBean
    private JdbcChatPublishCheckpointStore spiedCheckpointStore;

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @Autowired
    private ChatDeliveryReprocessAuditRepository auditRepository;

    @Test
    void 재적재_상한_격리는_대조_때_읽은_세대로만_기록해_그_사이_운영_재처리된_새_세대를_격리하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String group = ChatJobStream.DELIVERY.consumerGroup();
        // 재적재 상한에 닿은 채 Stream이 사라졌다
        jdbcClient.sql("update chats.chat_outbox set requeue_count = 2, last_requeued_at = null where delivery_id = :id")
                .param("id", deliveryId)
                .update();
        redis.delete(keys.deliveryStream());
        // 대조가 후보(세대 0)를 읽은 뒤 격리하기 전에, 다른 경로가 격리했고 운영자가 재처리해 세대 1로 다시 적재했다
        AtomicReference<List<RequeueCandidate>> stale = new AtomicReference<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RequeueCandidate> candidates = (List<RequeueCandidate>) invocation.callRealMethod();
            stale.set(candidates);
            assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.SOURCE_MISSING, null,
                    "다른 경로의 격리", ChatDeliveryClock.now())).isTrue();
            assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
            return candidates;
        }).doCallRealMethod().when(spiedOutboxStore)
                .findRequeueCandidates(eq(ChatJobStream.DELIVERY), anyLong(), any(), any(), anyInt());

        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();

        assertThat(stale.get()).singleElement().satisfies(candidate -> {
            assertThat(candidate.generation()).isZero();
            assertThat(candidate.requeueCount()).isEqualTo(2);
        });
        ChatPublishCheckpoint reopened = checkpoint(deliveryId);
        assertThat(reopened.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(reopened.getGeneration()).isEqualTo(1);
        assertThat(reopened.getDeadReason()).isNull();
        // 옛 세대의 발행 실패·상한 격리 결과도 새 세대를 덮지 않는다
        assertThat(spiedCheckpointStore.recordFailureOrIsolate(group, deliveryId, 0, "늦은 실패",
                ChatDeliveryClock.now(), null, 1, "0-1", ChatDeliveryClock.now())).isEmpty();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint(deliveryId).getTotalAttemptCount()).isZero();

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint(deliveryId).getGeneration()).isEqualTo(1);
        assertThat(fanout.messages()).singleElement().extracting(MessageFanout::deliveryId).isEqualTo(deliveryId);
        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(group, deliveryId)).singleElement()
                .satisfies(audit -> assertThat(audit.getPreviousDeadReason()).isEqualTo("SOURCE_MISSING"));
    }

    @Test
    void 같은_격리_작업을_동시에_재처리해도_한_번만_되돌리고_감사_기록과_재적재도_한_번이다() throws Exception {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String group = ChatJobStream.DELIVERY.consumerGroup();
        assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED, "0-1",
                "시도 상한", ChatDeliveryClock.now())).isTrue();
        clearInvocations(spiedQueue);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Boolean> results = new ArrayList<>();
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    start.await();
                    return reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).containsOnlyOnce(true);
        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(group, deliveryId)).singleElement()
                .satisfies(audit -> {
                    assertThat(audit.getPreviousGeneration()).isZero();
                    assertThat(audit.getPreviousPublishStatus()).isEqualTo("DEAD");
                });
        assertThat(checkpoint(deliveryId).getGeneration()).isEqualTo(1);
        verify(spiedQueue, times(1)).enqueue(eq(ChatJobStream.DELIVERY), anyMap());

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).singleElement().extracting(MessageFanout::deliveryId).isEqualTo(deliveryId);
    }

    @Test
    void 재처리의_상태_초기화가_실패하면_감사_기록도_롤백되고_다시_적재하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String group = ChatJobStream.DELIVERY.consumerGroup();
        assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED, "0-1",
                "시도 상한", ChatDeliveryClock.now())).isTrue();
        clearInvocations(spiedQueue);
        doThrow(new IllegalStateException("초기화 실패")).when(spiedOutboxStore)
                .resetRequeueForReprocess(eq(ChatJobStream.DELIVERY), eq(deliveryId), any());

        assertThatThrownBy(() -> reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId))
                .hasMessageContaining("초기화 실패");

        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(group, deliveryId)).isEmpty();
        ChatPublishCheckpoint unchanged = checkpoint(deliveryId);
        assertThat(unchanged.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(unchanged.getGeneration()).isZero();
        assertThat(unchanged.getDeadReason()).isEqualTo(ChatDeadReason.PUBLISH_RETRY_EXHAUSTED);
        assertThat(unchanged.getLastError()).isEqualTo("시도 상한");
        verify(spiedQueue, never()).enqueue(any(), anyMap());
    }

    @Test
    void 재처리의_즉시_재적재는_감사_기록과_초기화가_커밋된_뒤_트랜잭션_밖에서_한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String group = ChatJobStream.DELIVERY.consumerGroup();
        assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED, "0-1",
                "시도 상한", ChatDeliveryClock.now())).isTrue();
        AtomicReference<Boolean> inTransaction = new AtomicReference<>();
        AtomicReference<Long> committedAudits = new AtomicReference<>();
        AtomicReference<Integer> committedGeneration = new AtomicReference<>();
        doAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            // 트랜잭션 밖의 새 연결로 읽으므로 커밋된 값만 보인다
            committedAudits.set(jdbcClient.sql("""
                            select count(*) from chats.chat_delivery_reprocess_audit where job_id = :id
                            """).param("id", deliveryId).query(Long.class).single());
            committedGeneration.set(jdbcClient.sql("""
                            select generation from chats.chat_publish_checkpoint
                            where consumer_group = :group and job_id = :id and status = 'PENDING'
                            """).param("group", group).param("id", deliveryId).query(Integer.class).single());
            return invocation.callRealMethod();
        }).when(spiedQueue).enqueue(eq(ChatJobStream.DELIVERY), anyMap());

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();

        assertThat(inTransaction.get()).isFalse();
        assertThat(committedAudits.get()).isEqualTo(1);
        assertThat(committedGeneration.get()).isEqualTo(1);
        ChatPublishCheckpoint reopened = checkpoint(deliveryId);
        assertThat(reopened.getLastError()).isNull();
        assertThat(reopened.getDeadStreamEntryId()).isNull();
        assertThat(outbox(deliveryId).getRequeueCount()).isEqualTo(1);
    }
}
