package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.chat.app.ChatStreamJobProcessor.Outcome;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.JdbcChatOutboxStore;
import com.hapbang.chat.out.JdbcChatPublishCheckpointStore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stream 소비·회수·재구성의 경합 경계 (테스트 기준 "Stream 소비", "Pub/Sub·체크포인트", "Stream 유실·보존").
 * 회수 뒤 늦게 깨어난 옛 worker, 발행 중 운영 재처리로 바뀐 세대, 중복·역순 항목, 재시작 뒤 backlog, 회수 중 그룹 유실,
 * 재시도 대기 중인 미확인 항목과 재구성, 재구성 키셋 배치를 검증한다.
 */
// 새 컨텍스트의 DB 연결 풀이 남아 테스트 DB 연결 수 상한을 넘지 않도록 클래스가 끝나면 닫는다
@DirtiesContext
@TestPropertySource(properties = "hapbang.chat.delivery.reconstruct.batch-size=2")
class ChatStreamRaceTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @MockitoSpyBean
    private JdbcChatPublishCheckpointStore spiedCheckpointStore;

    @Autowired
    private JdbcChatOutboxStore outboxStore;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Test
    void 회수로_넘어간_작업을_멈췄던_옛_worker가_늦게_처리해도_다시_발행하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        // 읽은 뒤 GC·네트워크 지연으로 멈춘 worker
        QueuedJob stalled = queue.readNew(ChatJobStream.DELIVERY, "test-stalled:delivery:1", 10,
                Duration.ofMillis(50)).getFirst();

        await().pollDelay(Duration.ofMillis(150)).atMost(WAIT)
                .until(() -> processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10) > 0);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();

        assertThat(processor.handle(ChatJobStream.DELIVERY, stalled)).isEqualTo(Outcome.ALREADY_DONE);

        assertThat(fanout.publishCalls()).isEqualTo(1);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 발행_중_운영_재처리로_세대가_바뀌면_옛_세대_결과는_새_체크포인트를_덮지_않고_새_세대가_다시_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        String group = ChatJobStream.DELIVERY.consumerGroup();
        // 이 worker가 발행하는 동안 다른 worker가 시도 상한으로 격리했고, 운영자가 세대를 올려 재처리했다
        doAnswer(invocation -> {
            assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED,
                    job.entryId(), "다른 worker의 격리", ChatDeliveryClock.now())).isTrue();
            assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
            return invocation.callRealMethod();
        }).doCallRealMethod().when(spiedCheckpointStore)
                .markPublished(anyString(), any(), anyInt(), anyLong(), any());

        processor.handle(ChatJobStream.DELIVERY, job);

        ChatPublishCheckpoint reopened = checkpoint(deliveryId);
        assertThat(reopened.getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(reopened.getGeneration()).isEqualTo(1);
        // 옛 세대(0)의 실패·격리 결과도 새 세대를 덮지 않는다
        assertThat(spiedCheckpointStore.recordFailureOrIsolate(group, deliveryId, 0, "늦은 실패",
                ChatDeliveryClock.now(), null, 3, job.entryId(), ChatDeliveryClock.now())).isEmpty();
        assertThat(spiedCheckpointStore.markDead(group, deliveryId, 0, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED,
                job.entryId(), "늦은 격리", ChatDeliveryClock.now())).isFalse();
        assertThat(checkpoint(deliveryId).getAttemptCount()).isZero();

        // 재처리가 같은 안정 ID로 다시 적재한 항목을 새 세대로 발행한다
        pollAll(ChatJobStream.DELIVERY, WORKER);

        ChatPublishCheckpoint published = checkpoint(deliveryId);
        assertThat(published.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(published.getGeneration()).isEqualTo(1);
        assertThat(fanout.messages()).hasSize(2).extracting(MessageFanout::deliveryId).containsOnly(deliveryId);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 중복_항목을_역순으로_처리해도_작업마다_한_번_발행하고_순번으로_정렬할_수_있다() {
        UUID first = send("첫째");
        UUID second = send("둘째");
        relayAll(ChatJobStream.DELIVERY);
        // 적재 응답 유실·재구성으로 생긴 같은 작업의 중복 항목
        for (UUID deliveryId : List.of(first, second)) {
            queue.enqueue(ChatJobStream.DELIVERY,
                    ChatStreamEntries.fieldsOf(outboxStore.findJob(ChatJobStream.DELIVERY, deliveryId).orElseThrow()));
        }
        List<QueuedJob> jobs = new ArrayList<>(
                queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)));
        assertThat(jobs).hasSize(4);

        List<Outcome> outcomes = new ArrayList<>();
        for (QueuedJob job : jobs.reversed()) {
            outcomes.add(processor.handle(ChatJobStream.DELIVERY, job));
        }

        assertThat(outcomes).containsExactly(Outcome.PUBLISHED, Outcome.PUBLISHED, Outcome.ALREADY_DONE,
                Outcome.ALREADY_DONE);
        // 완료 순서는 적재 순서와 다를 수 있다. FE는 roomSequence로 정렬하고 messageId로 중복을 없앤다
        List<MessageFanout> published = fanout.messages();
        assertThat(published).extracting(MessageFanout::deliveryId).containsExactly(second, first);
        assertThat(published.get(0).message().roomSequence())
                .isGreaterThan(published.get(1).message().roomSequence());
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 재시작하면_새_consumer가_처리하지_못한_backlog를_건너뛰지_않고_읽는다() {
        List<UUID> deliveryIds = List.of(send("1"), send("2"), send("3"));
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob handled = queue.readNew(ChatJobStream.DELIVERY, WORKER, 1, Duration.ofMillis(50)).getFirst();
        processor.handle(ChatJobStream.DELIVERY, handled);

        // 재시작: 연결을 놓고, 그룹을 다시 확인(BUSYGROUP)한 뒤 새 이름의 consumer로 읽는다
        queue.releaseConsumer(WORKER);
        queue.ensureGroup(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, "test-restarted:delivery:1");

        assertThat(fanout.messages()).extracting(MessageFanout::deliveryId)
                .containsExactlyInAnyOrderElementsOf(deliveryIds);
        assertThat(deliveryIds).allSatisfy(deliveryId ->
                assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED));
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 형식이_잘못된_항목도_격리_기록이_실패하면_ACK하지_않고_기록한_뒤에만_ACK한다() {
        queue.enqueue(ChatJobStream.DELIVERY, Map.of("garbage", "1"));
        doThrow(new IllegalStateException("DB 중단")).doCallRealMethod().when(spiedCheckpointStore)
                .insertDeadIfAbsent(anyString(), any(), any(), anyString(), anyString(), any());
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.NOT_ACKED);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
        assertThat(checkpointRepository.findAll()).isEmpty();

        assertThat(processor.handle(ChatJobStream.DELIVERY, job)).isEqualTo(Outcome.ISOLATED);

        UUID syntheticId = UUID.nameUUIDFromBytes((ChatJobStream.DELIVERY.consumerGroup() + ":" + job.entryId())
                .getBytes(StandardCharsets.UTF_8));
        ChatPublishCheckpoint dead = checkpointRepository
                .findByConsumerGroupAndJobId(ChatJobStream.DELIVERY.consumerGroup(), syntheticId).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(dead.getDeadReason()).isEqualTo(ChatDeadReason.MALFORMED_ENTRY);
        assertThat(dead.getDeadStreamEntryId()).isEqualTo(job.entryId());
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        assertThat(fanout.publishCalls()).isZero();
    }

    @Test
    void 재시도를_기다리는_미확인_항목이_있으면_재구성이_다시_적재하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(1);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);

        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();

        assertThat(outbox(deliveryId).getRequeueCount()).isZero();
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    @Test
    void 회수_중_Stream과_그룹이_사라지면_그룹을_다시_만들고_요청된_재구성이_바로_다시_적재한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        // 정기 대조를 한 번 돌려 다음 주기(60초)까지는 요청이 있어야만 대조하게 만든다
        reconstructor.reconstructIfDue(ChatJobStream.DELIVERY);
        queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 10, Duration.ofMillis(50));
        redis.delete(keys.deliveryStream());

        assertThat(processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10)).isZero();

        assertThat(queue.groupState(ChatJobStream.DELIVERY)).isPresent();
        assertThat(reconstructor.reconstructIfDue(ChatJobStream.DELIVERY)).isEqualTo(1);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
    }

    @Test
    void 구독자_0명은_발행_실패와_별도의_지표로_남는다() {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        assertThat(registry).isNotNull();
        double before = zeroSubscriberCount(registry);
        UUID deliveryId = send("구독 단절 중");
        relayAll(ChatJobStream.DELIVERY);
        fanout.subscribers(0);

        pollAll(ChatJobStream.DELIVERY, WORKER);

        assertThat(zeroSubscriberCount(registry)).isEqualTo(before + 1);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    private static double zeroSubscriberCount(MeterRegistry registry) {
        Counter counter = registry.find("chat.delivery.publish.zero_subscribers")
                .tag("stream", ChatJobStream.DELIVERY.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 재구성은_배치_크기씩_체크포인트_키셋으로_이어_가며_모든_미완료_작업을_한_번씩_다시_적재한다() {
        List<UUID> deliveryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deliveryIds.add(send("메시지" + i));
        }
        relayAll(ChatJobStream.DELIVERY);
        redis.delete(keys.deliveryStream());

        List<Integer> rounds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rounds.add(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO));
        }

        // reconstruct.batch-size=2: 2, 2, 1, 그다음은 cooldown이라 0
        assertThat(rounds).containsExactly(2, 2, 1, 0);
        assertThat(deliveryIds).allSatisfy(deliveryId ->
                assertThat(outbox(deliveryId).getRequeueCount()).isEqualTo(1));
        pollAll(ChatJobStream.DELIVERY, WORKER);
        Set<UUID> published = new HashSet<>(fanout.messages().stream().map(MessageFanout::deliveryId).toList());
        assertThat(published).containsExactlyInAnyOrderElementsOf(deliveryIds);
        assertThat(fanout.messages()).hasSize(deliveryIds.size());
    }
}
