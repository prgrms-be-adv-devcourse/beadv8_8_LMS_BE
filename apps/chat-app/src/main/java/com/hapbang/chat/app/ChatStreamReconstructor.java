package com.hapbang.chat.app;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;
import com.hapbang.chat.app.ChatOutboxStore.OutboxJob;
import com.hapbang.chat.app.ChatOutboxStore.RequeueCandidate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 유실·후속 처리 지연 뒤 DB 미완료 작업을 다시 적재한다 (설계 4.5 "Redis 유실 복구와 보존").
 * <ul>
 *     <li>대상은 outbox가 ENQUEUED이고 필수 그룹의 체크포인트가 아직 PENDING인 작업이다. PUBLISHED·DEAD는 Redis 유실만으로
 *     다시 발행하지 않는다.</li>
 *     <li>체크포인트 ID 키셋으로 제한된 배치만 대조한다. 적재 항목이 아직 처리될 수 있으면(본문이 있고 미배분·미확인)
 *     다시 적재하지 않는다. 기본 회수는 미확인 목록(PEL)이 맡고, 이 대조는 유실·지연 복구 경로다.</li>
 *     <li>재적재는 처리권(재적재 시각)을 먼저 커밋하고 DB 트랜잭션 밖에서 같은 안정 ID로 적재한다. cooldown·횟수 상한을 둔다.
 *     상한에 닿았는데 마지막 항목도 처리될 수 없으면 체크포인트를 {@code REQUEUE_EXHAUSTED}로 격리한다(설계 4.5 7번).
 *     격리한 작업은 운영 재처리({@link #reprocess})로만 다시 처리한다.</li>
 *     <li>큐·그룹 유실(NOGROUP)을 발견하면 다음 대조는 적재 시각 기준 없이 바로 한다.</li>
 *     <li>Redis에 연결할 수 없거나 메모리 상한(OOM)이면 다시 적재하지 않고, 재적재 중 그렇게 되면 재적재 처리권을 횟수 없이
 *     되돌린다. 인프라 장애가 재적재 상한 격리로 이어지지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class ChatStreamReconstructor {

    private final ChatOutboxStore chatOutboxStore;
    private final ChatPublishCheckpointStore checkpointStore;
    private final ChatDeliveryQueue chatDeliveryQueue;
    private final ChatDeliveryProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ChatDeliveryAvailability availability;
    private final Map<ChatJobStream, State> states = new EnumMap<>(ChatJobStream.class);

    /** 큐·그룹 유실을 발견했다. 다음 대조를 적재 시각 기준 없이 바로 한다 */
    public void requestReconstruct(ChatJobStream stream) {
        synchronized (states) {
            state(stream).requested = true;
        }
    }

    /**
     * 주기가 됐거나 요청이 있으면 대조한다. 실행기는 짧은 간격으로 부른다.
     *
     * @return 다시 적재한 작업 수
     */
    public int reconstructIfDue(ChatJobStream stream) {
        if (!availability.redisAvailable()) {
            // 연결이 돌아온 뒤에 대조한다. 그룹 유실 요청은 남겨 둔다
            return 0;
        }
        LocalDateTime now = ChatDeliveryClock.now();
        boolean immediate;
        synchronized (states) {
            State state = state(stream);
            immediate = state.requested;
            boolean due = state.lastRunAt == null
                    || !state.lastRunAt.plus(properties.reconstruct().interval()).isAfter(now);
            if (!immediate && !due) {
                return 0;
            }
            state.requested = false;
            state.lastRunAt = now;
        }
        return reconstruct(stream, immediate ? Duration.ZERO : properties.reconstruct().enqueuedThreshold());
    }

    /**
     * 한 배치를 대조해 다시 적재한다. 지난 대조가 멈춘 위치부터 이어 가고, 끝까지 가면 처음으로 돌아간다.
     *
     * @param enqueuedThreshold 적재한 지 이 시간이 지난 작업만 대상이다
     * @return 다시 적재한 작업 수
     */
    public int reconstruct(ChatJobStream stream, Duration enqueuedThreshold) {
        assertNoTransaction();
        if (!availability.redisAvailable()) {
            return 0;
        }
        ChatDeliveryProperties.Reconstruct settings = properties.reconstruct();
        LocalDateTime now = ChatDeliveryClock.now();
        long after;
        synchronized (states) {
            after = state(stream).afterCheckpointId;
        }
        List<RequeueCandidate> candidates = chatOutboxStore.findRequeueCandidates(stream, after,
                now.minus(enqueuedThreshold), now.minus(settings.cooldown()), settings.batchSize());
        int requeued = requeueAll(stream, candidates);
        synchronized (states) {
            state(stream).afterCheckpointId = candidates.size() < settings.batchSize()
                    ? 0L
                    : candidates.getLast().checkpointId();
        }
        return requeued;
    }

    /**
     * 본문이 사라진 미확인 항목(회수 중 발견)의 작업을 바로 다시 적재한다. cooldown을 지키고, 상한에 닿았으면 격리한다.
     *
     * @return 다시 적재한 작업 수
     */
    public int recoverMissingEntries(ChatJobStream stream, Collection<String> streamEntryIds) {
        assertNoTransaction();
        if (!availability.redisAvailable()) {
            return 0;
        }
        ChatDeliveryProperties.Reconstruct settings = properties.reconstruct();
        LocalDateTime now = ChatDeliveryClock.now();
        return requeueAll(stream, chatOutboxStore.findRequeueCandidatesByEntryIds(stream, streamEntryIds,
                now.minus(settings.cooldown())));
    }

    /**
     * 운영 재처리. 권한 검사는 호출한 쪽이 한다. outbox나 그룹의 발행 체크포인트가 격리(DEAD)일 때만 한다.
     * <ol>
     *     <li>한 DB 트랜잭션에서 outbox → 체크포인트 순서로 행을 잠그고, 비우기 직전 상태를 감사 기록으로 남긴 뒤 상태를 되돌린다. 발행 체크포인트의 처리 세대를 1 올려
     *     옛 항목의 늦은 결과가 새 처리를 덮지 않게 한다. 이번 세대의 시도 횟수·현재 오류는 비우고 누적 횟수는 남긴다.</li>
     *     <li>적재 격리(outbox DEAD, 체크포인트는 보통 PENDING): outbox를 PENDING으로 돌려 relay가 같은 안정 ID로 다시 적재한다.</li>
     *     <li>발행 격리(체크포인트 DEAD: 발행 시도 상한·재적재 상한·원본 누락 등): 체크포인트를 PENDING으로 되돌리고,
     *     트랜잭션을 마친 뒤 같은 안정 ID로 바로 다시 적재한다.</li>
     * </ol>
     * 이 두 격리 외의 미완료 상태는 relay(lease 회수)·회수 루프·재구성이 스스로 진행하거나 상한에서 격리하므로 재처리 대상이
     * 아니다.
     *
     * @return 되돌렸으면 true. 어느 쪽도 격리 상태가 아니면 false
     */
    public boolean reprocess(ChatJobStream stream, UUID jobId) {
        return reprocess(stream, jobId, null);
    }

    /**
     * 운영 재처리. {@link #reprocess(ChatJobStream, UUID)}와 같고, 요청한 관리자를 감사 기록에 남긴다.
     *
     * @param requestedBy 요청한 관리자 회원 ID. 없으면 null
     */
    public boolean reprocess(ChatJobStream stream, UUID jobId, Long requestedBy) {
        assertNoTransaction();
        LocalDateTime now = ChatDeliveryClock.now();
        String group = stream.consumerGroup();
        Reprocessed reprocessed = transactionTemplate.execute(status -> {
            // 잠금 순서: outbox → 체크포인트. 잠근 뒤의 감사 스냅숏과 초기화가 같은 상태를 본다
            if (!chatOutboxStore.lockJob(stream, jobId)) {
                return Reprocessed.NONE;
            }
            checkpointStore.lock(group, jobId);
            if (chatOutboxStore.insertReprocessAudit(stream, jobId, requestedBy, now) == 0) {
                return Reprocessed.NONE;
            }
            if (chatOutboxStore.reopenDead(stream, jobId, now)) {
                checkpointStore.reopenUnpublished(group, jobId, now);
                return Reprocessed.BY_RELAY;
            }
            if (checkpointStore.reopenDead(group, jobId, now)) {
                return chatOutboxStore.resetRequeueForReprocess(stream, jobId, now)
                        ? Reprocessed.REQUEUE_NOW
                        : Reprocessed.BY_RELAY;
            }
            // 감사 기록 뒤 다른 처리가 먼저 상태를 바꿨다
            status.setRollbackOnly();
            return Reprocessed.NONE;
        });
        if (reprocessed == null || reprocessed == Reprocessed.NONE) {
            return false;
        }
        log.warn("운영 재처리로 작업을 되돌림 stream={}, jobId={}, mode={}", stream, jobId, reprocessed);
        if (reprocessed == Reprocessed.REQUEUE_NOW) {
            try {
                chatOutboxStore.findJob(stream, jobId).ifPresent(job -> requeue(stream, job));
            } catch (ChatDeliveryBackpressureException e) {
                availability.memoryFull(e);
                log.warn("Redis 메모리 상한으로 운영 재처리 뒤 즉시 재적재를 미룸, 재구성에 맡김 stream={}, jobId={}", stream, jobId);
            } catch (ChatDeliveryUnavailableException e) {
                availability.redisUnavailable(e);
                log.warn("Redis 연결 불가로 운영 재처리 뒤 즉시 재적재를 미룸, 재구성에 맡김 stream={}, jobId={}", stream, jobId);
            } catch (RuntimeException e) {
                // 되돌린 상태는 커밋됐다. 재구성이 처리될 수 없는 항목으로 보고 다시 적재한다
                log.error("운영 재처리 뒤 즉시 재적재 실패, 재구성에 맡김 stream={}, jobId={}", stream, jobId, e);
            }
        }
        return true;
    }

    private int requeueAll(ChatJobStream stream, List<RequeueCandidate> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }
        if (!ensureGroup(stream)) {
            return 0;
        }
        int requeued = 0;
        for (RequeueCandidate candidate : candidates) {
            try {
                if (candidate.streamEntryId() != null
                        && chatDeliveryQueue.isOutstanding(stream, candidate.streamEntryId())) {
                    continue;
                }
                if (candidate.requeueCount() >= properties.reconstruct().maxRequeues()) {
                    isolateRequeueExhausted(stream, candidate);
                    continue;
                }
                ChatDeliveryAvailability.WritePermit permit = availability.tryAcquireWrite();
                if (permit.gate() == ChatDeliveryAvailability.WriteGate.CLOSED) {
                    // 연결 불가·쓰기 거절 동안에는 다시 적재하지 않는다. 나머지는 다음 대조에서 다시 본다
                    break;
                }
                try {
                    if (requeue(stream, candidate.job())) {
                        requeued++;
                        availability.writeSucceeded(permit);
                    }
                } catch (ChatDeliveryBackpressureException e) {
                    // 재확인 권한을 돌려주기 전에 알려야 재확인 실패로 간격이 늘어난다
                    availability.memoryFull(permit, e);
                    throw e;
                } finally {
                    availability.writeEnded(permit);
                }
            } catch (ChatDeliveryBackpressureException e) {
                // 재적재 처리권은 requeue()가 되돌렸다. 쓰기 거절은 재적재 횟수에 넣지 않는다
                break;
            } catch (ChatDeliveryUnavailableException e) {
                // 연결이 끊겼다. 재적재 처리권은 requeue()가 되돌렸고, 나머지는 연결이 돌아온 뒤 다시 대조한다
                availability.redisUnavailable(e);
                break;
            } catch (RuntimeException e) {
                log.error("재적재 실패 stream={}, jobId={}", stream, candidate.job().jobId(), e);
            }
        }
        return requeued;
    }

    /**
     * 재적재 상한에 닿았는데 마지막 항목도 처리될 수 없는 작업을 격리한다. 대조 때 읽은 처리 세대를 조건으로 건다.
     * 원본 항목 위치는 마지막으로 적재한 위치를 남긴다.
     */
    private void isolateRequeueExhausted(ChatJobStream stream, RequeueCandidate candidate) {
        UUID jobId = candidate.job().jobId();
        boolean isolated = checkpointStore.markDead(stream.consumerGroup(), jobId, candidate.generation(),
                ChatDeadReason.REQUEUE_EXHAUSTED, candidate.streamEntryId(),
                "재적재 상한 초과(" + candidate.requeueCount() + "회): 마지막 항목이 처리될 수 없음",
                ChatDeliveryClock.now());
        if (isolated) {
            log.error("재적재 상한을 넘겨 작업을 격리함(운영 재처리 필요) stream={}, jobId={}, lastEntryId={}, requeues={}",
                    stream, jobId, candidate.streamEntryId(), candidate.requeueCount());
        }
    }

    /** 재적재 처리권을 커밋한 뒤 DB 트랜잭션 밖에서 같은 안정 ID로 적재한다 */
    private boolean requeue(ChatJobStream stream, OutboxJob job) {
        ChatDeliveryProperties.Reconstruct settings = properties.reconstruct();
        LocalDateTime requeuedAt = ChatDeliveryClock.now();
        if (!chatOutboxStore.claimRequeue(stream, job.id(), requeuedAt.minus(settings.cooldown()),
                settings.maxRequeues(), requeuedAt)) {
            return false;
        }
        String entryId;
        try {
            entryId = chatDeliveryQueue.enqueue(stream, ChatStreamEntries.fieldsOf(job));
        } catch (ChatDeliveryUnavailableException | ChatDeliveryBackpressureException e) {
            // 인프라 장애(연결 불가·메모리 상한)는 재적재 횟수에 넣지 않는다(재적재 상한 격리로 몰지 않는다)
            chatOutboxStore.releaseRequeueClaim(stream, job.id(), requeuedAt, ChatDeliveryClock.now());
            throw e;
        }
        if (!chatOutboxStore.recordRequeued(stream, job.id(), requeuedAt, entryId, ChatDeliveryClock.now())) {
            log.warn("다른 재구성이 먼저 기록해 재적재 위치를 기록하지 않음 stream={}, jobId={}, entryId={}", stream,
                    job.jobId(), entryId);
        }
        log.warn("미완료 작업을 다시 적재함 stream={}, jobId={}, entryId={}", stream, job.jobId(), entryId);
        return true;
    }

    /** @return Redis에 연결할 수 없으면 false */
    private boolean ensureGroup(ChatJobStream stream) {
        try {
            chatDeliveryQueue.ensureGroup(stream);
        } catch (ChatDeliveryUnavailableException e) {
            availability.redisUnavailable(e);
            return false;
        } catch (RuntimeException e) {
            log.error("consumer group을 확인하지 못함 stream={}", stream, e);
        }
        return true;
    }

    private State state(ChatJobStream stream) {
        return states.computeIfAbsent(stream, key -> new State());
    }

    private static void assertNoTransaction() {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "재구성은 DB 트랜잭션 밖에서 실행해야 합니다.");
    }

    private enum Reprocessed {
        /** 격리 상태가 아니어서 아무것도 바꾸지 않았다 */
        NONE,
        /** outbox를 PENDING으로 돌렸다. relay가 다시 적재한다 */
        BY_RELAY,
        /** 발행 체크포인트를 되돌렸다. 바로 다시 적재한다 */
        REQUEUE_NOW
    }

    private static final class State {
        private long afterCheckpointId;
        private LocalDateTime lastRunAt;
        private boolean requested;
    }
}
