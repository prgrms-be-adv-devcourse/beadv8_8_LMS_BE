package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.chat.app.dto.QueueGroupState;
import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * Redis 유실 재구성과 안전한 보존·정리 (테스트 기준 "Stream 유실·보존").
 */
@DirtiesContext
class ChatStreamRecoveryTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Autowired
    private ChatStreamTrimmer trimmer;

    @Test
    void 적재_뒤_Stream이_사라지면_DB_미완료_작업을_같은_안정_ID로_다시_적재해_발행한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        String lostEntryId = outbox(deliveryId).getStreamEntryId();
        redis.delete(keys.deliveryStream());

        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isEqualTo(1);

        ChatOutbox requeued = outbox(deliveryId);
        assertThat(requeued.getRequeueCount()).isEqualTo(1);
        assertThat(requeued.getLastRequeuedAt()).isNotNull();
        assertThat(requeued.getStreamEntryId()).isNotEqualTo(lostEntryId);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void 처리될_항목이_남아_있으면_다시_적재하지_않고_cooldown_안에서는_한_번만_적재한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);

        // 아직 배분되지 않은 정상 backlog
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();

        redis.delete(keys.deliveryStream());
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isEqualTo(1);
        redis.delete(keys.deliveryStream());
        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();
        assertThat(outbox(deliveryId).getRequeueCount()).isEqualTo(1);
    }

    @Test
    void 재적재_횟수_상한에_닿으면_더_적재하지_않는다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        for (int i = 0; i < 3; i++) {
            redis.delete(keys.deliveryStream());
            jdbcClient.sql("update chats.chat_outbox set last_requeued_at = null where delivery_id = :id")
                    .param("id", deliveryId)
                    .update();
            reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO);
        }

        assertThat(outbox(deliveryId).getRequeueCount()).isEqualTo(2);
        assertThat(streamLength(ChatJobStream.DELIVERY)).isZero();
        // 상한에 닿아 마지막 항목도 사라졌으면 멈춰 두지 않고 격리한다
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(checkpoint(deliveryId).getDeadReason()).isEqualTo(ChatDeadReason.REQUEUE_EXHAUSTED);
    }

    @Test
    void 발행을_끝낸_작업과_격리한_작업은_Redis_유실만으로_다시_적재하지_않는다() {
        UUID published = send("발행 완료");
        UUID dead = send("격리");
        relayAll(ChatJobStream.DELIVERY);
        List<QueuedJob> jobs = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50));
        processor.handle(ChatJobStream.DELIVERY, jobs.getFirst());
        fanout.failNext(3);
        for (int i = 0; i < 3; i++) {
            handleNow(jobs.get(1), dead);
        }
        assertThat(checkpoint(published).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint(dead).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        redis.delete(keys.deliveryStream());

        assertThat(reconstructor.reconstruct(ChatJobStream.DELIVERY, Duration.ZERO)).isZero();
        assertThat(streamLength(ChatJobStream.DELIVERY)).isZero();
    }

    @Test
    void 운영_재처리는_세대를_올려_격리한_작업을_다시_발행한다() {
        UUID deliveryId = send("격리");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        fanout.failNext(3);
        for (int i = 0; i < 3; i++) {
            handleNow(job, deliveryId);
        }
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);

        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isTrue();
        pollAll(ChatJobStream.DELIVERY, WORKER);

        var checkpoint = checkpoint(deliveryId);
        assertThat(checkpoint.getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint.getGeneration()).isEqualTo(1);
        assertThat(checkpoint.getLastError()).isNull();
        assertThat(checkpoint.getTotalAttemptCount()).isEqualTo(3);
        assertThat(fanout.messages()).singleElement()
                .satisfies(payload -> assertThat(payload.deliveryId()).isEqualTo(deliveryId));
        assertThat(reconstructor.reprocess(ChatJobStream.DELIVERY, deliveryId)).isFalse();
    }

    @Test
    void 그룹이_사라지면_읽기에서_그룹을_처음부터_다시_만들고_남은_작업을_처리한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        redis.opsForStream().destroyGroup(keys.deliveryStream(), ChatJobStream.DELIVERY.consumerGroup());

        assertThat(processor.pollNew(ChatJobStream.DELIVERY, WORKER)).isZero();
        assertThat(queue.groupState(ChatJobStream.DELIVERY)).isPresent();
        // 그룹 유실을 발견했으므로 다음 대조는 주기를 기다리지 않는다. 항목이 남아 있어 다시 적재하지는 않는다
        assertThat(reconstructor.reconstructIfDue(ChatJobStream.DELIVERY)).isZero();

        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void Stream과_그룹이_모두_사라지면_읽기_실패_뒤_요청된_재구성이_바로_다시_적재한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        reconstructor.reconstructIfDue(ChatJobStream.DELIVERY);
        redis.delete(keys.deliveryStream());

        assertThat(processor.pollNew(ChatJobStream.DELIVERY, WORKER)).isZero();
        assertThat(reconstructor.reconstructIfDue(ChatJobStream.DELIVERY)).isEqualTo(1);

        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void 본문이_사라진_미확인_항목은_성공으로_보지_않고_다시_적재한다() {
        UUID deliveryId = send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        QueuedJob read = queue.readNew(ChatJobStream.DELIVERY, "test-crashed:delivery:1", 10, Duration.ofMillis(50))
                .getFirst();
        redis.opsForStream().delete(keys.deliveryStream(), read.entryId());

        await().pollDelay(Duration.ofMillis(150)).atMost(WAIT).until(() -> {
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            return outbox(deliveryId).getRequeueCount() == 1;
        });

        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void ACK한_항목_본문은_남고_정리는_미확인_항목과_미배분_항목을_지우지_않는다() {
        UUID acked = send("확인됨");
        UUID pending = send("미확인");
        UUID undelivered = send("미배분");
        relayAll(ChatJobStream.DELIVERY);
        List<QueuedJob> read = queue.readNew(ChatJobStream.DELIVERY, WORKER, 2, Duration.ofMillis(50));
        processor.handle(ChatJobStream.DELIVERY, read.getFirst());
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(3);

        QueueGroupState state = queue.groupState(ChatJobStream.DELIVERY).orElseThrow();
        assertThat(state.pendingCount()).isEqualTo(1);
        assertThat(state.minPendingId()).isEqualTo(outbox(pending).getStreamEntryId());
        assertThat(trimmer.safeBoundary(ChatJobStream.DELIVERY, Instant.now()))
                .contains(outbox(pending).getStreamEntryId());

        assertThat(trimmer.trim(ChatJobStream.DELIVERY)).isEqualTo(1);

        assertThat(entryExists(outbox(acked).getStreamEntryId())).isFalse();
        assertThat(entryExists(outbox(pending).getStreamEntryId())).isTrue();
        assertThat(entryExists(outbox(undelivered).getStreamEntryId())).isTrue();
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(undelivered).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    @Test
    void 보존_기간_안의_항목은_확인됐어도_지우지_않는다() {
        UUID acked = send("확인됨");
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        String entryId = outbox(acked).getStreamEntryId();

        // 보존 기간 시작이 한 시간 전이면 경계는 그 시각이고, 방금 확인한 항목은 경계 뒤에 있다
        String boundary = trimmer.safeBoundary(ChatJobStream.DELIVERY, Instant.now().minusSeconds(3600))
                .orElseThrow();
        assertThat(ChatStreamEntries.compare(boundary, entryId)).isNegative();
        assertThat(queue.trimBefore(ChatJobStream.DELIVERY, boundary)).isZero();
        assertThat(entryExists(entryId)).isTrue();
    }

    @Test
    void 그룹이_없으면_정리하지_않는다() {
        send("안녕하세요");
        relayAll(ChatJobStream.DELIVERY);
        redis.opsForStream().destroyGroup(keys.deliveryStream(), ChatJobStream.DELIVERY.consumerGroup());

        assertThat(trimmer.trim(ChatJobStream.DELIVERY)).isZero();
        assertThat(streamLength(ChatJobStream.DELIVERY)).isEqualTo(1);
    }

    private boolean entryExists(String entryId) {
        List<MapRecord<String, Object, Object>> entries = redis.opsForStream().range(keys.deliveryStream(),
                Range.closed(entryId, entryId));
        return entries != null && !entries.isEmpty();
    }
}
