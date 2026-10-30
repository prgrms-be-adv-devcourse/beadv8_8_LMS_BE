package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;
import com.hapbang.chat.app.ChatOutboxStore.ClaimedJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB outbox → 작업 큐 적재 (설계 4.5 "DB 저장과 Stream 적재").
 * <ol>
 *     <li>처리권이 만료된 행을 되돌린다(relay 중단 회수).</li>
 *     <li>작은 배치를 {@code FOR UPDATE SKIP LOCKED}로 선점하고 처리권({@code claimToken}·{@code leaseUntil})을 커밋한다.</li>
 *     <li>DB 트랜잭션 밖에서 적재한다.</li>
 *     <li>처리권이 그대로일 때만 결과(ENQUEUED·항목 위치, 또는 실패·backoff·DEAD)를 기록한다.</li>
 * </ol>
 * 적재 응답 유실·적재 직후 종료로 같은 작업의 항목이 여러 개 생길 수 있다. consumer가 체크포인트로 흡수한다.
 * relay는 발행 체크포인트를 건드리지 않는다. Redis에 연결할 수 없거나({@link ChatDeliveryUnavailableException}) 메모리
 * 상한으로 쓰기가 거절되면({@link ChatDeliveryBackpressureException}) 선점을 시도 횟수 없이 되돌리고
 * {@link ChatDeliveryAvailability}가 재확인할 때까지 멈춘다. 연결된 Redis가 그 밖의 이유로 명령을 거절한 실패만 적재 시도로 센다.
 */
@Slf4j
@Service
@ConditionalOnChatDeliveryPipeline
@RequiredArgsConstructor
public class ChatOutboxRelay {

    private final ChatOutboxStore chatOutboxStore;
    private final ChatDeliveryQueue chatDeliveryQueue;
    private final ChatDeliveryProperties properties;
    private final ChatDeliveryAvailability availability;

    /**
     * 한 배치를 적재한다.
     *
     * @return 이번에 선점한 행 수
     */
    public int relayBatch(ChatJobStream stream) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "outbox relay는 DB 트랜잭션 밖에서 실행해야 합니다.");
        if (!availability.redisAvailable()) {
            // Redis에 연결할 수 없는 동안에는 선점하지 않는다. 작업은 PENDING으로 남고 시도 횟수도 늘지 않는다
            return 0;
        }
        ChatDeliveryProperties.Relay relay = properties.relay();
        LocalDateTime now = ChatDeliveryClock.now();
        int released = chatOutboxStore.releaseExpiredLeases(stream, now, relay.batchSize(), relay.maxAttempts());
        if (released > 0) {
            log.warn("처리권이 만료된 outbox를 되돌림 stream={}, count={}", stream, released);
        }
        // 적재 허락은 DB 준비가 끝난 뒤에 받고, 재확인 권한(PROBE)은 결과와 관계없이 끝에서 돌려준다
        ChatDeliveryAvailability.WritePermit permit = availability.tryAcquireWrite();
        if (permit.gate() == ChatDeliveryAvailability.WriteGate.CLOSED) {
            // Redis 연결 불가·쓰기 거절 동안에는 선점하지 않는다
            return 0;
        }
        try {
            // 쓰기 거절 재확인 차례면 한 건만 적재해 본다
            int limit = permit.isProbe() ? 1 : relay.batchSize();
            List<ClaimedJob> claimed = chatOutboxStore.claim(stream, UUID.randomUUID(), now, now.plus(relay.lease()),
                    limit);
            for (int i = 0; i < claimed.size(); i++) {
                if (!enqueue(stream, claimed.get(i), permit)) {
                    releaseWithoutAttempt(stream, claimed.subList(i, claimed.size()));
                    break;
                }
            }
            return claimed.size();
        } finally {
            availability.writeEnded(permit);
        }
    }

    /**
     * @return 큐에 연결할 수 없어 멈춰야 하면 false
     */
    private boolean enqueue(ChatJobStream stream, ClaimedJob claimed, ChatDeliveryAvailability.WritePermit permit) {
        long id = claimed.job().id();
        String entryId;
        try {
            entryId = chatDeliveryQueue.enqueue(stream, ChatStreamEntries.fieldsOf(claimed.job()));
        } catch (ChatDeliveryUnavailableException e) {
            availability.redisUnavailable(e);
            return false;
        } catch (ChatDeliveryBackpressureException e) {
            // 메모리 상한은 작업의 실패가 아니다. 미적재 작업을 outbox에 남기고 적재를 멈춘다(설계 4.5)
            availability.memoryFull(permit, e);
            return false;
        } catch (RuntimeException e) {
            if (ChatDeliveryErrors.isInterrupted(e)) {
                // 종료 중 인터럽트는 작업의 실패도 장애도 아니다. 선점을 시도 없이 되돌린다
                return false;
            }
            // 연결된 Redis가 명령을 거절했다(WRONGTYPE·ERR 등). 작업의 적재 실패로 센다
            recordFailure(stream, claimed, e);
            return true;
        }
        availability.writeSucceeded(permit);
        if (!chatOutboxStore.markEnqueued(stream, id, claimed.claimToken(), entryId, ChatDeliveryClock.now())) {
            // 처리권이 만료돼 다른 relay가 가져갔다. 적재한 항목은 중복으로 남고 consumer가 흡수한다
            log.warn("처리권이 바뀌어 적재 결과를 기록하지 않음 stream={}, jobId={}, entryId={}", stream,
                    claimed.job().jobId(), entryId);
        }
        return true;
    }

    /** Redis 연결 불가·메모리 상한으로 적재하지 못한 선점을 시도 횟수 없이 PENDING으로 되돌린다 */
    private void releaseWithoutAttempt(ChatJobStream stream, List<ClaimedJob> claimed) {
        LocalDateTime now = ChatDeliveryClock.now();
        for (ClaimedJob job : claimed) {
            chatOutboxStore.releaseClaim(stream, job.job().id(), job.claimToken(),
                    "Redis 연결 불가·메모리 상한으로 적재 보류(시도 횟수에 넣지 않음)", now);
        }
        log.warn("Redis 연결 불가·메모리 상한으로 선점한 outbox를 되돌림 stream={}, count={}", stream, claimed.size());
    }

    private void recordFailure(ChatJobStream stream, ClaimedJob claimed, RuntimeException cause) {
        ChatDeliveryProperties.Relay relay = properties.relay();
        LocalDateTime now = ChatDeliveryClock.now();
        Optional<ChatOutboxStatus> status = chatOutboxStore.markEnqueueFailed(stream, claimed.job().id(),
                claimed.claimToken(), "적재 실패: " + ChatDeliveryErrors.describe(cause), ChatRetryBackoff.nextAttemptAt(relay.backoff(),
                        claimed.attemptCount(), now), relay.maxAttempts(), now);
        if (status.isEmpty()) {
            log.warn("처리권이 바뀌어 적재 실패를 기록하지 않음 stream={}, jobId={}", stream, claimed.job().jobId());
        } else if (status.get() == ChatOutboxStatus.DEAD) {
            log.error("적재 시도 상한을 넘겨 outbox를 격리함 stream={}, jobId={}, attempts={}", stream,
                    claimed.job().jobId(), claimed.attemptCount(), cause);
        } else {
            log.warn("적재 실패, 다시 시도함 stream={}, jobId={}, attempts={}: {}", stream, claimed.job().jobId(),
                    claimed.attemptCount(), cause.toString());
        }
    }
}
