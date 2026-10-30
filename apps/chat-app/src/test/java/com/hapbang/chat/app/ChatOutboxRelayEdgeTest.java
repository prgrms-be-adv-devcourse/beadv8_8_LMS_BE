package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.ChatControlOutboxRepository;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.chat.out.ChatPublishCheckpointRepository;
import com.hapbang.chat.out.JdbcChatOutboxStore;

/**
 * Outbox 적재의 남은 경계 (테스트 기준 "Outbox 적재", 설계 9장 "메시지 outbox relay").
 * 처리권 만료의 시도 상한, 여러 relay의 동시 선점, 적재 실패 격리의 운영 재처리, 체크포인트 저장 실패의 원자성,
 * 연결 제어 기록의 중복 흡수를 검증한다.
 */
// 새 컨텍스트의 DB 연결 풀이 남아 테스트 DB 연결 수 상한을 넘지 않도록 클래스가 끝나면 닫는다
@DirtiesContext
@TestPropertySource(properties = "hapbang.chat.delivery.relay.batch-size=3")
class ChatOutboxRelayEdgeTest extends ChatDeliveryTestSupport {

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @MockitoSpyBean
    private ChatPublishCheckpointRepository spiedCheckpointRepository;

    @Autowired
    private JdbcChatOutboxStore outboxStore;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatConnectionControl chatConnectionControl;

    @Autowired
    private ChatControlOutboxRepository controlOutboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 선점_뒤_종료가_시도_상한까지_반복되면_처리권_회수가_DEAD로_격리하고_다시_가져가지_않는다() {
        UUID deliveryId = send("안녕하세요");
        // relay.max-attempts=2: 선점만 하고 적재 전에 종료한 relay가 두 번 있었다
        crashAfterClaim();
        expireLease(deliveryId);
        assertThat(outboxStore.releaseExpiredLeases(ChatJobStream.DELIVERY, ChatDeliveryClock.now(), 10, 2))
                .isEqualTo(1);
        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        crashAfterClaim();
        expireLease(deliveryId);

        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isZero();

        ChatOutbox dead = outbox(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatOutboxStatus.DEAD);
        assertThat(dead.getAttemptCount()).isEqualTo(2);
        assertThat(dead.getClaimToken()).isNull();
        assertThat(dead.getLastError()).contains("처리권 만료");
        assertThat(streamLength(ChatJobStream.DELIVERY)).isZero();
        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isZero();
        // relay는 발행 체크포인트를 건드리지 않는다
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
    }

    @Test
    void 여러_relay가_동시에_선점해도_작업마다_한_번만_선점해_적재한다() throws Exception {
        List<UUID> deliveryIds = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            deliveryIds.add(send("메시지" + i));
        }

        int relays = 4;
        ExecutorService executor = Executors.newFixedThreadPool(relays);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < relays; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    int claimed = 0;
                    int batch;
                    while ((batch = relay.relayBatch(ChatJobStream.DELIVERY)) > 0) {
                        claimed += batch;
                    }
                    return claimed;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(deliveryIds.size());
        } finally {
            executor.shutdownNow();
        }

        assertThat(deliveryIds).allSatisfy(deliveryId -> {
            ChatOutbox outbox = outbox(deliveryId);
            assertThat(outbox.getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
        });
        Set<Object> enqueuedJobIds = new HashSet<>();
        redis.opsForStream().range(keys.deliveryStream(), Range.unbounded())
                .forEach(entry -> enqueuedJobIds.add(entry.getValue().get(ChatStreamEntries.JOB_ID)));
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(deliveryIds.size());
        assertThat(enqueuedJobIds).hasSize(deliveryIds.size());
    }

    /**
     * 설계 4.5 7번·9장 "적재 실패 DEAD·재처리": 적재 상한을 넘겨 격리한 outbox도 권한 있는 운영자가 다시 처리할 수 있어야 한다.
     * 적재 실패 격리는 outbox만 DEAD이고 발행 체크포인트는 PENDING으로 남는다.
     */
    @Test
    void 적재_실패로_격리한_outbox도_운영_재처리로_다시_적재해_발행한다() {
        UUID deliveryId = send("안녕하세요");
        doThrow(new IllegalStateException("Redis 중단")).when(spiedQueue)
                .enqueue(eq(ChatJobStream.DELIVERY), anyMap());
        relay.relayBatch(ChatJobStream.DELIVERY);
        waitUntilDue(deliveryId);
        relay.relayBatch(ChatJobStream.DELIVERY);
        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.DEAD);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        reset(spiedQueue);

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
    }

    @Test
    void 발행_체크포인트_저장이_실패하면_메시지와_outbox도_남지_않는다() {
        doThrow(new IllegalStateException("체크포인트 저장 실패")).when(spiedCheckpointRepository)
                .save(any(ChatPublishCheckpoint.class));

        assertThatThrownBy(() -> chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chatMessageRepository.findByChatRoom_IdOrderByRoomSequenceAsc(chatRoomId)).isEmpty();
        assertThat(chatOutboxRepository.findByChatRoomIdOrderByIdAsc(chatRoomId)).isEmpty();
        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 연결_제어_기록은_같은_회원_버전_종류를_한_번만_남기고_호출한_트랜잭션을_롤백시키지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            chatConnectionControl.requestRecheck(GUEST_ID, 5L, ChatControlReason.MEMBER_MODIFIED);
            chatConnectionControl.requestRecheck(GUEST_ID, 5L, ChatControlReason.MEMBER_BACKFILL);
            chatConnectionControl.requestRecheck(GUEST_ID, null, ChatControlReason.MEMBER_MODIFIED);
            chatConnectionControl.requestRecheck(GUEST_ID, null, ChatControlReason.MEMBER_MODIFIED);
            assertThat(status.isRollbackOnly()).isFalse();
        });

        assertThat(controlOutboxRepository.findAll())
                .extracting(control -> String.valueOf(control.getMemberVersion()))
                .containsExactlyInAnyOrder("5", "null");
        assertThat(checkpointRepository.findAll())
                .filteredOn(checkpoint -> checkpoint.getConsumerGroup().equals(ChatJobStream.CONTROL.consumerGroup()))
                .hasSize(2);
        assertThatThrownBy(() -> chatConnectionControl.requestRecheck(GUEST_ID, 6L,
                ChatControlReason.MEMBER_MODIFIED))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 회원_반영이_롤백되면_연결_제어_작업도_남지_않는다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            chatConnectionControl.requestRecheck(GUEST_ID, 9L, ChatControlReason.MEMBER_MODIFIED);
            throw new IllegalStateException("회원 반영 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(controlOutboxRepository.findAll()).isEmpty();
        assertThat(checkpointRepository.findAll()).isEmpty();
        assertThat(relay.relayBatch(ChatJobStream.CONTROL)).isZero();
    }

    /** 선점만 커밋하고 적재 전에 종료한 relay */
    private void crashAfterClaim() {
        LocalDateTime now = ChatDeliveryClock.now();
        assertThat(outboxStore.claim(ChatJobStream.DELIVERY, UUID.randomUUID(), now, now.plusSeconds(30), 10))
                .hasSize(1);
    }

    private void expireLease(UUID deliveryId) {
        jdbcClient.sql("update chats.chat_outbox set lease_until = :past where delivery_id = :id")
                .param("past", ChatDeliveryClock.now().minusSeconds(1))
                .param("id", deliveryId)
                .update();
    }

    private void waitUntilDue(UUID deliveryId) {
        jdbcClient.sql("update chats.chat_outbox set next_attempt_at = :past where delivery_id = :id")
                .param("past", ChatDeliveryClock.now().minusSeconds(1))
                .param("id", deliveryId)
                .update();
    }
}
