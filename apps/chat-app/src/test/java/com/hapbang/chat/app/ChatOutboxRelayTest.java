package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.ChatOutboxStore.ClaimedJob;
import com.hapbang.chat.domain.ChatControlOutbox;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;
import com.hapbang.chat.out.ChatControlOutboxRepository;
import com.hapbang.chat.out.JdbcChatOutboxStore;

/**
 * DB outbox → Stream 적재 (테스트 기준 "Outbox 적재").
 */
@DirtiesContext
class ChatOutboxRelayTest extends ChatDeliveryTestSupport {

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @Autowired
    private JdbcChatOutboxStore outboxStore;

    @Autowired
    private ChatConnectionControl chatConnectionControl;

    @Autowired
    private ChatControlOutboxRepository controlOutboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    void 배치_실행이_꺼져_있으면_파이프라인을_켜도_실행기를_만들지_않는다() {
        assertThat(context.getBeanNamesForType(ChatOutboxRelay.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ChatDeliveryPipelineRunner.class)).isEmpty();
    }

    @Test
    void 적재하면_ENQUEUED와_항목_위치를_기록하고_항목에는_참조만_싣는다() {
        UUID deliveryId = send("안녕하세요");

        relayAll(ChatJobStream.DELIVERY);

        ChatOutbox outbox = outbox(deliveryId);
        assertThat(outbox.getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getClaimToken()).isNull();
        List<MapRecord<String, Object, Object>> entries = redis.opsForStream().range(keys.deliveryStream(),
                Range.closed(outbox.getStreamEntryId(), outbox.getStreamEntryId()));
        assertThat(entries).singleElement().satisfies(entry -> assertThat(entry.getValue())
                .containsEntry(ChatStreamEntries.JOB_ID, deliveryId.toString())
                .containsEntry(ChatStreamEntries.TYPE, "MESSAGE")
                .containsEntry(ChatStreamEntries.MESSAGE_ID, outbox.getMessageId().toString())
                .containsEntry(ChatStreamEntries.RECIPIENT_SET_ID, outbox.getRecipientSetId().toString())
                .containsEntry(ChatStreamEntries.NOTIFY_RULE, "RECIPIENTS_EXCEPT_SENDER")
                .containsEntry(ChatStreamEntries.SCHEMA_VERSION, "1")
                .doesNotContainKey("content"));
        // 적재는 발행 체크포인트를 바꾸지 않는다
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
    }

    @Test
    void 선점_직후_종료되면_처리권이_만료된_뒤_다른_relay가_다시_적재한다() {
        UUID deliveryId = send("안녕하세요");
        LocalDateTime now = ChatDeliveryClock.now();
        // 선점만 커밋하고 적재 전에 종료된 relay
        List<ClaimedJob> crashed = outboxStore.claim(ChatJobStream.DELIVERY, UUID.randomUUID(), now,
                now.plusSeconds(30), 10);
        assertThat(crashed).hasSize(1);

        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isZero();
        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.PROCESSING);

        expireLease(deliveryId);
        relayAll(ChatJobStream.DELIVERY);

        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(outbox(deliveryId).getAttemptCount()).isEqualTo(2);
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    @Test
    void 처리권이_바뀐_뒤_도착한_옛_relay의_결과는_기록하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        LocalDateTime now = ChatDeliveryClock.now();
        ClaimedJob late = outboxStore.claim(ChatJobStream.DELIVERY, UUID.randomUUID(), now, now.plusSeconds(30), 10)
                .getFirst();
        expireLease(deliveryId);
        relayAll(ChatJobStream.DELIVERY);
        String currentEntryId = outbox(deliveryId).getStreamEntryId();

        // 옛 relay가 늦게 적재를 마쳤다
        String lateEntryId = queue.enqueue(ChatJobStream.DELIVERY, ChatStreamEntries.fieldsOf(late.job()));
        boolean recorded = outboxStore.markEnqueued(ChatJobStream.DELIVERY, late.job().id(), late.claimToken(),
                lateEntryId, ChatDeliveryClock.now());
        var failed = outboxStore.markEnqueueFailed(ChatJobStream.DELIVERY, late.job().id(), late.claimToken(),
                "늦은 실패", ChatDeliveryClock.now(), 1, ChatDeliveryClock.now());

        assertThat(recorded).isFalse();
        assertThat(failed).isEmpty();
        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(outbox(deliveryId).getStreamEntryId()).isEqualTo(currentEntryId);
    }

    @Test
    void 적재_뒤_결과_기록_전에_종료되면_다시_적재해_중복_항목이_생기고_consumer가_한_번만_발행한다() {
        UUID deliveryId = send("안녕하세요");
        // XADD는 됐지만 결과를 기록하기 전에 종료: 결과 기록 대신 처리권 만료로 흉내 낸다
        LocalDateTime now = ChatDeliveryClock.now();
        ClaimedJob crashed = outboxStore.claim(ChatJobStream.DELIVERY, UUID.randomUUID(), now, now.plusSeconds(30),
                10).getFirst();
        queue.enqueue(ChatJobStream.DELIVERY, ChatStreamEntries.fieldsOf(crashed.job()));
        expireLease(deliveryId);
        relayAll(ChatJobStream.DELIVERY);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(2);
        assertThat(fanout.messages()).hasSize(1);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void consumer가_relay의_결과_기록보다_먼저_처리해도_relay가_체크포인트를_덮지_않는다() {
        UUID deliveryId = send("안녕하세요");
        LocalDateTime now = ChatDeliveryClock.now();
        ClaimedJob claimed = outboxStore.claim(ChatJobStream.DELIVERY, UUID.randomUUID(), now, now.plusSeconds(30),
                10).getFirst();
        String entryId = queue.enqueue(ChatJobStream.DELIVERY, ChatStreamEntries.fieldsOf(claimed.job()));
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);

        outboxStore.markEnqueued(ChatJobStream.DELIVERY, claimed.job().id(), claimed.claimToken(), entryId,
                ChatDeliveryClock.now());

        assertThat(outbox(deliveryId).getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void 적재가_실패하면_backoff로_다시_시도하고_상한을_넘기면_DEAD로_격리한다() {
        UUID deliveryId = send("안녕하세요");
        doThrow(new IllegalStateException("Redis 중단")).when(spiedQueue)
                .enqueue(eq(ChatJobStream.DELIVERY), anyMap());

        relay.relayBatch(ChatJobStream.DELIVERY);
        ChatOutbox first = outbox(deliveryId);
        assertThat(first.getStatus()).isEqualTo(ChatOutboxStatus.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getLastError()).contains("Redis 중단");

        waitUntilDue(deliveryId);
        relay.relayBatch(ChatJobStream.DELIVERY);

        ChatOutbox dead = outbox(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatOutboxStatus.DEAD);
        assertThat(dead.getAttemptCount()).isEqualTo(2);
        // DEAD는 relay가 다시 가져가지 않는다
        reset(spiedQueue);
        assertThat(relay.relayBatch(ChatJobStream.DELIVERY)).isZero();
        assertThat(streamLength(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 연결_제어_작업도_제어_Stream에_적재한다() {
        transactionTemplate.executeWithoutResult(status ->
                chatConnectionControl.requestRecheck(GUEST_ID, 5L, ChatControlReason.MEMBER_MODIFIED));

        relayAll(ChatJobStream.CONTROL);

        ChatControlOutbox control = controlOutboxRepository.findAll().getFirst();
        assertThat(control.getStatus()).isEqualTo(ChatOutboxStatus.ENQUEUED);
        List<MapRecord<String, Object, Object>> entries = redis.opsForStream().range(keys.controlStream(),
                Range.unbounded());
        assertThat(entries).singleElement().satisfies(entry -> assertThat(entry.getValue())
                .containsEntry(ChatStreamEntries.JOB_ID, control.getControlId().toString())
                .containsEntry(ChatStreamEntries.TYPE, "RECHECK_USER")
                .containsEntry(ChatStreamEntries.USER_ID, GUEST_ID.toString())
                .containsEntry(ChatStreamEntries.MEMBER_VERSION, "5")
                .containsEntry(ChatStreamEntries.REASON, "MEMBER_MODIFIED"));
        assertThat(streamLength(ChatJobStream.DELIVERY)).isZero();
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
