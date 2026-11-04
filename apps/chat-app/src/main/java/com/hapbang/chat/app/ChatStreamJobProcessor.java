package com.hapbang.chat.app;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.app.dto.ReclaimedJobs;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;
import com.hapbang.chat.app.ChatPublishCheckpointStore.CheckpointState;
import com.hapbang.chat.app.ChatPublishCheckpointStore.FailureResult;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 작업 큐의 항목을 처리한다: 체크포인트 확인 → payload 생성 → Pub/Sub 발행 → 체크포인트 PUBLISHED → ACK
 * (설계 4.5 "그룹 소비·Pub/Sub 발행·ACK").
 * <ul>
 *     <li>발행은 DB 트랜잭션 밖에서 한다. 체크포인트 갱신은 문장 하나의 짧은 조건부 UPDATE다.</li>
 *     <li>ACK는 체크포인트가 PUBLISHED 또는 DEAD로 커밋된 뒤에만 한다. 체크포인트·격리 기록이 실패하면 ACK하지 않아
 *     미확인 목록에 남기고, 회수 루프가 다시 처리한다.</li>
 *     <li>발행 뒤 체크포인트 기록 전에 종료하면 다시 발행될 수 있다(중복 허용). 체크포인트 기록 뒤 ACK 전에 종료하면
 *     다음 처리에서 PUBLISHED를 확인하고 발행 없이 ACK한다.</li>
 *     <li>Redis 연결 불가와 구독자 0명({@link #isOwnSubscriptionDown})은 인프라 장애다. 시도 횟수에 넣지 않고 ACK하지 않으며,
 *     {@link ChatDeliveryAvailability}가 재확인할 때까지 그 큐를 멈춘다(2026-10-30 사용자 결정).</li>
 *     <li>연결된 Redis에서의 발행 실패는 시도 횟수·다음 시도 시각만 기록하고 ACK하지 않는다.
 *     시도 상한에 닿으면 실패 기록과 같은 문장에서 DEAD로 격리하고, 커밋된 뒤 ACK한다. 상한 이상인 PENDING은 발행하지 않고
 *     먼저 격리한다.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnChatDeliveryPipeline
public class ChatStreamJobProcessor {

    /** 회수 한 번에 cursor를 이어 갈 최대 횟수. 본문 없는 항목만 계속 나와도 끝나게 한다 */
    private static final int MAX_RECLAIM_ROUNDS = 10;

    private final ChatDeliveryQueue chatDeliveryQueue;
    private final ChatPublishCheckpointStore checkpointStore;
    private final ChatFanoutPayloadLoader payloadLoader;
    private final RealtimeChatFanout realtimeChatFanout;
    private final ChatStreamReconstructor reconstructor;
    private final ChatDeliveryProperties properties;
    private final ChatDeliveryAvailability availability;
    private final MeterRegistry meterRegistry;
    private final Map<ChatJobStream, String> reclaimCursors = new EnumMap<>(ChatJobStream.class);

    public ChatStreamJobProcessor(ChatDeliveryQueue chatDeliveryQueue, ChatPublishCheckpointStore checkpointStore,
            ChatFanoutPayloadLoader payloadLoader, RealtimeChatFanout realtimeChatFanout,
            ChatStreamReconstructor reconstructor, ChatDeliveryProperties properties,
            ChatDeliveryAvailability availability, ObjectProvider<MeterRegistry> meterRegistry) {
        this.chatDeliveryQueue = chatDeliveryQueue;
        this.checkpointStore = checkpointStore;
        this.payloadLoader = payloadLoader;
        this.realtimeChatFanout = realtimeChatFanout;
        this.reconstructor = reconstructor;
        this.properties = properties;
        this.availability = availability;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** 항목 하나를 처리한 결과 */
    public enum Outcome {
        /** 이번에 발행하고 체크포인트를 PUBLISHED로 기록한 뒤 ACK했다 */
        PUBLISHED,
        /**
         * 이미 PUBLISHED·DEAD인 작업의 중복 항목이라 발행 없이 ACK했다. 발행했지만 그 사이 다른 worker가 끝냈거나
         * 처리 세대가 바뀌어 결과를 기록하지 않고 ACK한 경우도 포함한다
         */
        ALREADY_DONE,
        /** 격리(DEAD)를 기록한 뒤 ACK했다 */
        ISOLATED,
        /** 발행 실패를 시도로 기록했다. ACK하지 않아 미확인 목록에 남는다 */
        RETRY_LATER,
        /**
         * 인프라 장애(Redis 연결 불가·자기 구독 단절)로 발행하지 않았거나 발행이 완료되지 않았다. 시도 횟수에 넣지 않고 ACK하지 않아
         * 미확인 목록에 남는다. 장애가 풀리면 회수 루프가 다시 처리한다
         */
        PAUSED,
        /** 다음 시도 시각 전이라 손대지 않았다. 미확인 목록에 남는다 */
        DEFERRED,
        /** DB 기록·ACK가 실패해 미확인 목록에 남겼다 */
        NOT_ACKED
    }

    /**
     * 이 consumer로 새 작업을 읽어 처리한다. 큐·그룹이 없으면 그룹을 다시 만들고 DB 재구성을 요청한다.
     *
     * @return 읽은 항목 수
     */
    public int pollNew(ChatJobStream stream, String consumer) {
        ChatDeliveryProperties.Consumer settings = properties.consumer();
        if (!availability.mayConsume(stream)) {
            // Redis 연결 불가·자기 구독 단절 동안에는 새 작업을 읽지 않는다. worker가 바쁘게 돌지 않게 잠깐 기다린다
            availability.awaitRetry(stream, settings.block());
            return 0;
        }
        List<QueuedJob> jobs;
        try {
            jobs = chatDeliveryQueue.readNew(stream, consumer, settings.readCount(), settings.block());
        } catch (ChatQueueGroupMissingException e) {
            recoverMissingGroup(stream, e);
            return 0;
        } catch (ChatDeliveryUnavailableException e) {
            availability.redisUnavailable(e);
            return 0;
        }
        for (QueuedJob job : jobs) {
            handleSafely(stream, job);
        }
        return jobs.size();
    }

    /**
     * 오래 확인되지 않은 미확인 작업을 이 consumer로 회수해 처리한다. 지난 회수가 멈춘 cursor부터 이어 간다.
     * 본문이 사라진 미확인 항목은 성공으로 보지 않고 DB 재구성으로 넘긴다.
     *
     * @param budget 이번에 처리할 최대 항목 수
     * @return 처리한 항목 수
     */
    public int reclaim(ChatJobStream stream, String consumer, int budget) {
        ChatDeliveryProperties.Reclaim settings = properties.reclaim();
        if (!availability.redisAvailable()) {
            return 0;
        }
        String cursor;
        synchronized (reclaimCursors) {
            cursor = reclaimCursors.getOrDefault(stream, ChatStreamEntries.START);
        }
        int processed = 0;
        // 처음부터 시작했으면 cursor가 0-0으로 돌아올 때 한 바퀴다. 중간부터 시작했으면 한 번 처음으로 돌아가 이어 본다
        boolean wrapped = ChatStreamEntries.START.equals(cursor);
        for (int round = 0; round < MAX_RECLAIM_ROUNDS && processed < budget; round++) {
            ReclaimedJobs reclaimed;
            try {
                reclaimed = chatDeliveryQueue.reclaim(stream, consumer, settings.minIdle(), cursor,
                        Math.min(settings.count(), budget - processed));
            } catch (ChatQueueGroupMissingException e) {
                recoverMissingGroup(stream, e);
                cursor = ChatStreamEntries.START;
                break;
            } catch (ChatDeliveryUnavailableException e) {
                availability.redisUnavailable(e);
                break;
            }
            if (!reclaimed.deletedEntryIds().isEmpty()) {
                log.error("본문이 사라진 미확인 항목을 DB 재구성으로 넘김 stream={}, entryIds={}", stream,
                        reclaimed.deletedEntryIds());
                count("chat.delivery.pending.body_missing", stream, reclaimed.deletedEntryIds().size());
                reconstructor.recoverMissingEntries(stream, reclaimed.deletedEntryIds());
            }
            for (QueuedJob job : reclaimed.jobs()) {
                handleSafely(stream, job);
                processed++;
            }
            cursor = reclaimed.nextCursor();
            if (ChatStreamEntries.START.equals(cursor)) {
                if (wrapped) {
                    break;
                }
                wrapped = true;
            }
        }
        synchronized (reclaimCursors) {
            reclaimCursors.put(stream, cursor);
        }
        // 구독 단절로 멈춘 큐인데 이번 회수에서 시험 발행이 없었으면(시험 발행할 작업이 없거나 결과 없이 끝났다) 잠정적으로 연다
        availability.reopenIfNothingToProbe(stream);
        return processed;
    }

    /**
     * 항목 하나를 처리한다. DB 트랜잭션 밖에서 부른다.
     */
    public Outcome handle(ChatJobStream stream, QueuedJob job) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "Stream 항목은 DB 트랜잭션 밖에서 처리해야 합니다.");
        String group = stream.consumerGroup();
        Optional<UUID> parsed = ChatStreamEntries.jobIdOf(job);
        if (parsed.isEmpty()) {
            UUID syntheticId = UUID.nameUUIDFromBytes(
                    (group + ":" + job.entryId()).getBytes(StandardCharsets.UTF_8));
            return isolateWithoutCheckpoint(stream, job, syntheticId, ChatDeadReason.MALFORMED_ENTRY,
                    "작업 ID를 읽을 수 없는 항목: " + job.fields());
        }
        UUID jobId = parsed.get();
        Optional<CheckpointState> found = checkpointStore.find(group, jobId);
        if (found.isEmpty()) {
            return isolateWithoutCheckpoint(stream, job, jobId, ChatDeadReason.SOURCE_MISSING,
                    "발행 체크포인트가 없습니다: " + job.fields());
        }
        CheckpointState checkpoint = found.get();
        if (checkpoint.isDone()) {
            return ack(stream, job, Outcome.ALREADY_DONE);
        }
        LocalDateTime now = ChatDeliveryClock.now();
        if (checkpoint.nextAttemptAt() != null && checkpoint.nextAttemptAt().isAfter(now)) {
            return Outcome.DEFERRED;
        }
        if (checkpoint.attemptCount() >= properties.consumer().maxAttempts()) {
            // 실패 기록은 상한에서 DEAD까지 한 문장으로 남기므로 정상 경로에서는 오지 않는다. 상한을 낮춘 설정 변경 등으로
            // 상한 이상의 PENDING이 남아 있으면 발행해 완료시키지 않고 먼저 격리한다
            return isolate(stream, job, jobId, checkpoint, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED,
                    "발행 시도 상한에 닿은 작업을 발행 전에 격리(" + checkpoint.attemptCount() + "회)");
        }
        ChatDeliveryAvailability.PublishPermit permit = availability.tryAcquirePublish(stream);
        if (!permit.granted()) {
            // Redis 연결 불가이거나, 자기 구독이 끊겨 이 큐를 멈췄고 이번 항목이 재확인 발행 차례가 아니다
            return Outcome.PAUSED;
        }
        long subscribers;
        try {
            Optional<Long> published;
            try {
                published = publish(stream, jobId);
            } catch (ChatDeliveryUnavailableException e) {
                availability.redisUnavailable(e);
                return recordPaused(stream, jobId, checkpoint, "Redis 연결 불가: " + ChatDeliveryErrors.describe(e),
                        null);
            } catch (RuntimeException e) {
                if (ChatDeliveryErrors.isInterrupted(e)) {
                    // 종료 중 인터럽트: 작업의 실패도 장애도 아니다. 시도로 세지 않고 미확인 목록에 남긴다
                    return Outcome.NOT_ACKED;
                }
                String error = "발행 실패: " + ChatDeliveryErrors.describe(e);
                if (!availability.confirmRedisConnected()) {
                    // 발행 구현이 연결 불가를 다른 예외로 알렸다. 같은 Redis가 응답하지 않으므로 인프라 장애로 본다
                    return recordPaused(stream, jobId, checkpoint, error, null);
                }
                return recordFailure(stream, job, jobId, checkpoint, error, null);
            }
            if (published.isEmpty()) {
                return isolate(stream, job, jobId, checkpoint, ChatDeadReason.SOURCE_MISSING,
                        "전달 원본(outbox·메시지·수신자 집합)이 없습니다.");
            }
            subscribers = published.get();
            if (isOwnSubscriptionDown(subscribers)) {
                log.warn("구독자 0명이라 발행 완료로 보지 않음(자기 구독 단절) stream={}, jobId={}, entryId={}", stream,
                        jobId, job.entryId());
                count("chat.delivery.publish.zero_subscribers", stream, 1);
                availability.noSubscriber(permit);
                return recordPaused(stream, jobId, checkpoint, "구독자 0명(자기 구독 단절)", subscribers);
            }
            availability.published(permit);
        } finally {
            // 시험 발행 권한은 결과와 관계없이 돌려준다(published·noSubscriber가 이미 돌려줬으면 아무 일도 없다)
            availability.publishEnded(permit);
        }

        boolean marked;
        try {
            marked = checkpointStore.markPublished(group, jobId, checkpoint.generation(), subscribers,
                    ChatDeliveryClock.now());
        } catch (RuntimeException e) {
            log.error("발행했지만 체크포인트를 기록하지 못해 ACK하지 않음 stream={}, jobId={}", stream, jobId, e);
            return Outcome.NOT_ACKED;
        }
        if (!marked) {
            // 다른 worker가 먼저 끝냈거나 운영 재처리로 세대가 바뀌었다. 새 세대는 자기 항목으로 다시 처리한다.
            // 이 항목은 ACK하되 이번 발행을 완료로 기록하지 않았으므로 결과를 구분한다
            log.info("체크포인트가 이미 바뀌어 발행 결과를 기록하지 않음 stream={}, jobId={}", stream, jobId);
            return ack(stream, job, Outcome.ALREADY_DONE);
        }
        return ack(stream, job, Outcome.PUBLISHED);
    }

    /**
     * 발행 명령이 성공했지만 구독자가 0명인지. replica 1개에서 구독자는 이 앱 자신뿐이므로 0명은 자기 구독 단절이다
     * (2026-10-29 사용자 결정). 발행 완료로 기록하지 않고 ACK하지 않는다. 인프라 장애이므로 시도 횟수에 넣지 않고, 구독이 돌아올
     * 때까지 그 큐를 멈춘다(2026-10-30 사용자 결정). 구독자가 1명 이상이면 온라인 회원 수와 관계없이 발행 완료다
     * (오프라인 회원을 위해 다시 발행하지 않는다).
     */
    static boolean isOwnSubscriptionDown(long subscriberCount) {
        return subscriberCount <= 0;
    }

    private Optional<Long> publish(ChatJobStream stream, UUID jobId) {
        return switch (stream) {
            case DELIVERY -> payloadLoader.loadMessage(jobId).map(realtimeChatFanout::publishMessage);
            case CONTROL -> payloadLoader.loadControl(jobId).map(realtimeChatFanout::publishControl);
        };
    }

    /**
     * 발행 실패를 기록한다. 시도 상한에 닿으면 같은 문장에서 DEAD까지 기록하므로, 실패 기록과 격리 사이의 종료로 상한에 닿은
     * PENDING이 남지 않는다. DEAD가 커밋된 뒤에만 ACK한다. 기록이 실패하면 ACK하지 않는다.
     */
    /** 인프라 장애로 발행하지 못했음을 남긴다. 시도 횟수에 넣지 않고 ACK하지 않는다 */
    private Outcome recordPaused(ChatJobStream stream, UUID jobId, CheckpointState checkpoint, String error,
            Long subscriberCount) {
        try {
            checkpointStore.recordPaused(stream.consumerGroup(), jobId, checkpoint.generation(), error,
                    subscriberCount, ChatDeliveryClock.now());
        } catch (RuntimeException e) {
            log.warn("인프라 장애 기록 실패 stream={}, jobId={}", stream, jobId, e);
        }
        return Outcome.PAUSED;
    }

    private Outcome recordFailure(ChatJobStream stream, QueuedJob job, UUID jobId, CheckpointState checkpoint,
            String error, Long subscriberCount) {
        ChatDeliveryProperties.Consumer settings = properties.consumer();
        LocalDateTime now = ChatDeliveryClock.now();
        Optional<FailureResult> result;
        try {
            result = checkpointStore.recordFailureOrIsolate(stream.consumerGroup(), jobId, checkpoint.generation(),
                    error, ChatRetryBackoff.nextAttemptAt(settings.backoff(), checkpoint.attemptCount() + 1, now),
                    subscriberCount, settings.maxAttempts(), job.entryId(), now);
        } catch (RuntimeException e) {
            log.error("발행 실패·격리를 기록하지 못해 ACK하지 않음 stream={}, jobId={}", stream, jobId, e);
            return Outcome.NOT_ACKED;
        }
        if (result.isEmpty()) {
            return ackIfDone(stream, job, jobId);
        }
        if (result.get().status() == ChatPublishStatus.DEAD) {
            log.error("발행 시도 상한을 넘겨 작업을 격리함 stream={}, jobId={}, entryId={}, attempts={}: {}", stream, jobId,
                    job.entryId(), result.get().attemptCount(), error);
            count("chat.delivery.isolated", stream, 1);
            return ackIfDone(stream, job, jobId) == Outcome.ALREADY_DONE ? Outcome.ISOLATED : Outcome.NOT_ACKED;
        }
        log.warn("발행 실패, 미확인 목록에 남겨 다시 시도함 stream={}, jobId={}, attempts={}: {}", stream, jobId,
                result.get().attemptCount(), error);
        return Outcome.RETRY_LATER;
    }

    /** 체크포인트를 DEAD로 기록한 뒤에만 ACK한다. 기록이 실패하면 ACK하지 않는다 */
    private Outcome isolate(ChatJobStream stream, QueuedJob job, UUID jobId, CheckpointState checkpoint,
            ChatDeadReason reason, String error) {
        try {
            checkpointStore.markDead(stream.consumerGroup(), jobId, checkpoint.generation(), reason, job.entryId(),
                    error, ChatDeliveryClock.now());
        } catch (RuntimeException e) {
            log.error("격리를 기록하지 못해 ACK하지 않음 stream={}, jobId={}, reason={}", stream, jobId, reason, e);
            return Outcome.NOT_ACKED;
        }
        log.error("작업을 격리함 stream={}, jobId={}, entryId={}, reason={}: {}", stream, jobId, job.entryId(), reason,
                error);
        count("chat.delivery.isolated", stream, 1);
        return ackIfDone(stream, job, jobId) == Outcome.ALREADY_DONE ? Outcome.ISOLATED : Outcome.NOT_ACKED;
    }

    /** 체크포인트가 없는 작업은 격리 행을 새로 남긴 뒤에만 ACK한다 */
    private Outcome isolateWithoutCheckpoint(ChatJobStream stream, QueuedJob job, UUID jobId, ChatDeadReason reason,
            String error) {
        try {
            checkpointStore.insertDeadIfAbsent(stream.consumerGroup(), jobId, reason, job.entryId(), error,
                    ChatDeliveryClock.now());
        } catch (RuntimeException e) {
            log.error("격리를 기록하지 못해 ACK하지 않음 stream={}, entryId={}, reason={}", stream, job.entryId(),
                    reason, e);
            return Outcome.NOT_ACKED;
        }
        log.error("작업을 격리함 stream={}, jobId={}, entryId={}, reason={}: {}", stream, jobId, job.entryId(), reason,
                error);
        count("chat.delivery.isolated", stream, 1);
        return ackIfDone(stream, job, jobId) == Outcome.ALREADY_DONE ? Outcome.ISOLATED : Outcome.NOT_ACKED;
    }

    /** 체크포인트가 완료(PUBLISHED·DEAD)로 커밋돼 있을 때만 ACK한다 */
    private Outcome ackIfDone(ChatJobStream stream, QueuedJob job, UUID jobId) {
        Optional<CheckpointState> current;
        try {
            current = checkpointStore.find(stream.consumerGroup(), jobId);
        } catch (RuntimeException e) {
            log.error("체크포인트를 확인하지 못해 ACK하지 않음 stream={}, jobId={}", stream, jobId, e);
            return Outcome.NOT_ACKED;
        }
        if (current.isPresent() && current.get().isDone()) {
            return ack(stream, job, Outcome.ALREADY_DONE);
        }
        return Outcome.NOT_ACKED;
    }

    private Outcome ack(ChatJobStream stream, QueuedJob job, Outcome outcome) {
        try {
            chatDeliveryQueue.ack(stream, job.entryId());
            return outcome;
        } catch (RuntimeException e) {
            // 미확인 목록에 남는다. 회수 때 완료된 체크포인트를 확인하고 발행 없이 ACK한다
            if (e instanceof ChatDeliveryUnavailableException unavailable) {
                availability.redisUnavailable(unavailable);
            }
            log.warn("ACK 실패 stream={}, entryId={}", stream, job.entryId(), e);
            return Outcome.NOT_ACKED;
        }
    }

    private void handleSafely(ChatJobStream stream, QueuedJob job) {
        try {
            handle(stream, job);
        } catch (RuntimeException e) {
            log.error("Stream 항목 처리 중 예외, 미확인 목록에 남김 stream={}, entryId={}", stream, job.entryId(), e);
        }
    }

    private void recoverMissingGroup(ChatJobStream stream, ChatQueueGroupMissingException cause) {
        log.error("작업 큐나 consumer group이 없어 다시 만들고 DB 재구성을 요청함 stream={}", stream, cause);
        count("chat.delivery.group_missing", stream, 1);
        try {
            chatDeliveryQueue.ensureGroup(stream);
        } catch (ChatDeliveryUnavailableException e) {
            availability.redisUnavailable(e);
        } catch (RuntimeException e) {
            log.error("consumer group을 다시 만들지 못함 stream={}", stream, e);
        }
        reconstructor.requestReconstruct(stream);
    }

    private void count(String name, ChatJobStream stream, int amount) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, "stream", stream.name()).increment(amount);
        }
    }
}
