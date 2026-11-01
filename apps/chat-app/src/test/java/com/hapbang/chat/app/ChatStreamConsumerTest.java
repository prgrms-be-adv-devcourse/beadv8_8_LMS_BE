package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.ChatStreamJobProcessor.Outcome;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.JpaChatPublishCheckpointStore;

/**
 * Stream 그룹 소비·Pub/Sub 발행·체크포인트·ACK·PEL 회수 (테스트 기준 "Stream 소비", "Pub/Sub·체크포인트").
 */
@DirtiesContext
class ChatStreamConsumerTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @MockitoSpyBean
    private JpaChatPublishCheckpointStore spiedCheckpointStore;

    @Autowired
    private ChatConnectionControl chatConnectionControl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 메시지를_불변_집합으로_펼쳐_발행하고_PUBLISHED_기록_뒤_ACK한다() {
        Long messageId = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "c-1").message().messageId();
        UUID deliveryId = deliveryIdOf(messageId);
        relayAll(ChatJobStream.DELIVERY);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        MessageFanout payload = fanout.messages().getFirst();
        assertThat(payload.deliveryId()).isEqualTo(deliveryId);
        assertThat(payload.type()).isEqualTo(MessageFanout.Type.MESSAGE);
        assertThat(payload.roomId()).isEqualTo(chatRoomId);
        // 수신자를 펼치지 않고 저장 때 가리킨 수신자 집합 버전만 싣는다(방 생성 = 버전 1)
        assertThat(payload.recipientSetVersion()).isEqualTo(1);
        assertThat(payload.notifyRule()).isEqualTo(ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER);
        assertThat(payload.message().messageId()).isEqualTo(messageId);
        assertThat(payload.message().clientMessageId()).isNull();
        assertThat(payload.senderClientMessageId()).isEqualTo("c-1");
        ChatPublishCheckpoint checkpoint = checkpoint(deliveryId);
        assertThat(checkpoint.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint.getLastSubscriberCount()).isEqualTo(1);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        // ACK는 미확인 목록에서만 지운다. 본문은 남는다
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    @Test
    void 퇴장_안내는_남은_참여자에게만_알림_없이_발행한다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        relayAll(ChatJobStream.DELIVERY);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        MessageFanout payload = fanout.messages().getFirst();
        // 퇴장을 반영한 집합(버전 2)을 가리키고 알림 대상이 없다
        assertThat(payload.recipientSetVersion()).isEqualTo(2);
        assertThat(payload.notifyRule()).isEqualTo(ChatNotifyRule.NONE);
    }

    @Test
    void 저장_뒤_재입장한_회원은_이전_메시지_payload의_수신자가_아니다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        relayAll(ChatJobStream.DELIVERY);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        // 퇴장 안내, 재입장 안내 모두 재입장 전 집합(버전 2, 호스트만)을 가리킨다
        assertThat(fanout.messages()).hasSize(2)
                .allSatisfy(payload -> assertThat(payload.recipientSetVersion()).isEqualTo(2));
    }

    @Test
    void 연결_제어_작업은_제어_그룹에서_발행한다() {
        transactionTemplate.executeWithoutResult(status ->
                chatConnectionControl.requestRecheck(GUEST_ID, null, ChatControlReason.MEMBER_BACKFILL));
        relayAll(ChatJobStream.CONTROL);

        pollAll(ChatJobStream.CONTROL, "test:control:1");

        assertThat(fanout.controls()).singleElement().satisfies(control -> {
            assertThat(control.type()).isEqualTo(ChatControlType.RECHECK_USER);
            assertThat(control.userId()).isEqualTo(GUEST_ID);
            assertThat(control.memberVersion()).isNull();
            assertThat(control.reason()).isEqualTo(ChatControlReason.MEMBER_BACKFILL);
        });
        assertThat(checkpointRepository.findByConsumerGroupAndJobId(ChatJobStream.CONTROL.consumerGroup(),
                fanout.controls().getFirst().controlId()).orElseThrow().getStatus())
                .isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(pendingCount(ChatJobStream.CONTROL)).isZero();
    }

    @Test
    void 같은_그룹의_worker들이_작업을_나눠_받고_모두_한_번씩_발행한다() {
        for (int i = 0; i < 6; i++) {
            send("메시지" + i);
        }
        relayAll(ChatJobStream.DELIVERY);

        List<QueuedJob> first = queue.readNew(ChatJobStream.DELIVERY, "test:delivery:1", 3, Duration.ofMillis(50));
        List<QueuedJob> second = queue.readNew(ChatJobStream.DELIVERY, "test:delivery:2", 3, Duration.ofMillis(50));
        Set<String> firstIds = new HashSet<>(first.stream().map(QueuedJob::entryId).toList());
        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3).noneMatch(job -> firstIds.contains(job.entryId()));
        first.forEach(job -> processor.handle(ChatJobStream.DELIVERY, job));
        second.forEach(job -> processor.handle(ChatJobStream.DELIVERY, job));

        assertThat(fanout.messages()).hasSize(6);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 그룹을_다시_확인해도_위치를_바꾸지_않아_재시작_뒤_처리한_작업을_다시_읽지_않는다() {
        send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        // 재시작: 그룹 확인(BUSYGROUP)과 새 consumer 이름
        queue.ensureGroup(ChatJobStream.DELIVERY);
        queue.releaseConsumer(WORKER);

        assertThat(processor.pollNew(ChatJobStream.DELIVERY, "test-restarted:delivery:1")).isZero();
        assertThat(fanout.publishCalls()).isEqualTo(1);
    }

    @Test
    void 읽은_뒤_처리하지_못하고_종료한_작업은_회수_루프가_cursor를_이어_가며_처리한다() {
        for (int i = 0; i < 5; i++) {
            send("메시지" + i);
        }
        relayAll(ChatJobStream.DELIVERY);
        // 읽기만 하고 종료한 worker
        assertThat(queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 10, Duration.ofMillis(50)))
                .hasSize(5);
        assertThat(processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10)).isZero();

        // reclaim.count=2라 cursor를 여러 번 이어 가야 다섯 개를 모두 가져온다
        await().pollDelay(Duration.ofMillis(150)).atMost(WAIT)
                .until(() -> processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10) > 0
                        && fanout.messages().size() == 5);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 발행이_실패하면_ACK하지_않고_시도를_기록하며_회수로_다시_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(1);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint failed = checkpoint(deliveryId);
        assertThat(failed.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isNotNull();
        assertThat(failed.getLastError()).contains("주입한 발행 실패");
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);

        await().pollDelay(Duration.ofMillis(150)).atMost(WAIT)
                .until(() -> processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10) > 0);

        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).hasSize(1);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 다음_시도_시각_전에는_회수해도_발행하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        sql.update("update chats.chat_publish_checkpoint set next_attempt_at = :future where job_id = :id",
                Map.of("future", LocalDateTime.now().plusMinutes(10), "id", deliveryId));

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.DEFERRED);

        assertThat(fanout.publishCalls()).isZero();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
        clearBackoff(deliveryId);
        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.PUBLISHED);
    }

    @Test
    void 발행_시도_상한을_넘기면_DEAD로_격리한_뒤_ACK하고_중복_항목은_발행_없이_ACK한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(3);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        assertThat(handleNow(job, deliveryId)).isEqualTo(Outcome.RETRY_LATER);
        assertThat(handleNow(job, deliveryId)).isEqualTo(Outcome.RETRY_LATER);
        assertThat(handleNow(job, deliveryId)).isEqualTo(Outcome.ISOLATED);

        ChatPublishCheckpoint dead = checkpoint(deliveryId);
        assertThat(dead.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(dead.getDeadReason()).isEqualTo(ChatDeadReason.PUBLISH_RETRY_EXHAUSTED);
        assertThat(dead.getDeadStreamEntryId()).isEqualTo(job.entryId());
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();

        // 같은 작업의 중복 항목
        queue.enqueue(ChatJobStream.DELIVERY, Map.of(ChatStreamEntries.JOB_ID, deliveryId.toString()));
        int callsBefore = fanout.publishCalls();
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(fanout.publishCalls()).isEqualTo(callsBefore);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 격리_기록이_실패하면_ACK하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(3);
        // 상한에 닿는 세 번째 실패 기록(실패+격리를 한 문장으로 기록)이 DB 오류로 실패한다
        doCallRealMethod().doCallRealMethod().doThrow(new IllegalStateException("DB 중단"))
                .when(spiedCheckpointStore).recordFailureOrIsolate(anyString(), any(), anyInt(), anyString(), any(),
                        any(), anyInt(), anyString(), any());
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        handleNow(job, deliveryId);
        handleNow(job, deliveryId);
        assertThat(handleNow(job, deliveryId)).isEqualTo(Outcome.NOT_ACKED);

        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint(deliveryId).getAttemptCount()).isEqualTo(2);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    @Test
    void 발행_뒤_체크포인트_기록_전에_실패하면_ACK하지_않고_다시_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        doThrow(new IllegalStateException("DB 중단")).doCallRealMethod().when(spiedCheckpointStore)
                .markPublished(anyString(), any(), anyInt(), anyLong(), any());
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.NOT_ACKED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.PUBLISHED);
        // exactly-once가 아니다: 같은 deliveryId로 두 번 발행했다. FE가 messageId로 중복을 제거한다
        assertThat(fanout.messages()).hasSize(2).extracting(MessageFanout::deliveryId).containsOnly(deliveryId);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 체크포인트_기록_뒤_ACK_전에_실패하면_다음_처리에서_발행_없이_ACK한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        doThrow(new IllegalStateException("Redis 중단")).doCallRealMethod().when(spiedQueue)
                .ack(eq(ChatJobStream.DELIVERY), anyString());
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.NOT_ACKED);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.ALREADY_DONE);
        assertThat(fanout.publishCalls()).isEqualTo(1);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 구독자가_0명이면_완료로_보지_않고_구독이_돌아온_뒤_회수로_다시_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.subscribers(0);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint incomplete = checkpoint(deliveryId);
        assertThat(incomplete.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        // 구독 단절은 인프라 장애라 시도 횟수에 넣지 않는다(2026-10-30 사용자 결정)
        assertThat(incomplete.getAttemptCount()).isZero();
        assertThat(incomplete.getLastSubscriberCount()).isZero();
        assertThat(incomplete.getLastError()).contains("구독자 0명");
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
        assertThat(fanout.messages()).isEmpty();

        fanout.subscribers(1);
        await().pollDelay(Duration.ofMillis(150)).atMost(WAIT)
                .until(() -> processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10) > 0);

        ChatPublishCheckpoint published = checkpoint(deliveryId);
        assertThat(published.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(published.getLastSubscriberCount()).isEqualTo(1);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 구독자_0명이_시도_상한보다_많이_이어져도_시도_횟수를_늘리지_않고_격리하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.subscribers(0);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            outcomes.add(handleNow(job, deliveryId));
            // 재확인 시각을 넘겨 매번 시험 발행이 되게 한다
            await().pollDelay(Duration.ofMillis(210)).atMost(WAIT).until(() -> true);
        }

        assertThat(outcomes).containsOnly(Outcome.PAUSED);
        assertThat(fanout.publishCalls()).isGreaterThanOrEqualTo(4);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint(deliveryId).getAttemptCount()).isZero();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    @Test
    void 작업_ID를_읽을_수_없는_항목과_원본이_없는_항목은_격리를_기록한_뒤_ACK한다() {
        queue.enqueue(ChatJobStream.DELIVERY, Map.of("garbage", "1"));
        UUID unknown = UUID.randomUUID();
        queue.enqueue(ChatJobStream.DELIVERY, Map.of(ChatStreamEntries.JOB_ID, unknown.toString()));

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(fanout.publishCalls()).isZero();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        assertThat(checkpointRepository.findAll())
                .extracting(ChatPublishCheckpoint::getDeadReason)
                .contains(ChatDeadReason.MALFORMED_ENTRY, ChatDeadReason.SOURCE_MISSING);
        assertThat(checkpointRepository.findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), unknown)
                .orElseThrow().getStatus()).isEqualTo(ChatPublishStatus.DEAD);
    }

    @Test
    void 다른_그룹은_같은_Stream을_독립적으로_소비하고_ACK한다() {
        send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String otherGroup = "other-feature-v1";
        redis.opsForStream().createGroup(keys.deliveryStream(), ReadOffset.from("0-0"), otherGroup);

        pollAll(ChatJobStream.DELIVERY, WORKER);
        var otherRead = redis.opsForStream().read(Consumer.from(otherGroup, "other-1"),
                StreamOffset.create(keys.deliveryStream(), ReadOffset.lastConsumed()));

        assertThat(otherRead).hasSize(1);
        assertThat(redis.opsForStream().pending(keys.deliveryStream(), otherGroup).getTotalPendingMessages())
                .isEqualTo(1);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        redis.opsForStream().acknowledge(keys.deliveryStream(), otherGroup, otherRead.getFirst().getId());
        assertThat(redis.opsForStream().pending(keys.deliveryStream(), otherGroup).getTotalPendingMessages())
                .isZero();
    }

    @Test
    void 종료_중_인터럽트로_끝난_발행은_시도로_세지_않고_멈춤으로도_보내지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        fanout.interruptNextMessage();

        Outcome outcome;
        try {
            outcome = processor.handle(ChatJobStream.DELIVERY, job);
        } finally {
            // 이 테스트 스레드의 인터럽트 표시를 지운다
            Thread.interrupted();
        }

        assertThat(outcome).isEqualTo(Outcome.NOT_ACKED);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(checkpoint(deliveryId).getAttemptCount()).isZero();
        assertThat(availability.redisAvailable()).isTrue();
        assertThat(availability.mayConsume(ChatJobStream.DELIVERY)).isTrue();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.PUBLISHED);
    }
}
