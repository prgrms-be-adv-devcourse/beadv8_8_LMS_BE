package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hapbang.chat.app.dto.ChatIsolatedJob;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatDeliveryType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutboxStatus;

/**
 * 전달·제어 outbox의 적재 상태를 바꾸는 저장 계약. relay·재구성·운영 재처리가 쓴다.
 * <ul>
 *     <li>변경은 조건부로만 한다. 적재 결과는 처리권({@code claimToken})이 그대로일 때만 기록해 늦은 결과를 버린다.</li>
 *     <li>호출한 쪽이 트랜잭션을 열지 않으면 호출마다 바로 커밋된다. 선점을 커밋한 뒤 큐에 적재해 큐 I/O 동안 행 잠금을 잡지 않는다.</li>
 *     <li>발행 체크포인트({@link ChatPublishCheckpointStore})는 바꾸지 않는다. 적재 결과가 consumer의 진행을 덮지 않는다.</li>
 * </ul>
 */
public interface ChatOutboxStore {

    /** 적재할 outbox 행 */
    sealed interface OutboxJob permits DeliveryJob, ControlJob {

        long id();

        /** {@code deliveryId} 또는 {@code controlId} */
        UUID jobId();
    }

    record DeliveryJob(long id, UUID jobId, ChatDeliveryType type, long messageId, long roomId, long roomSequence,
            long recipientSetId, ChatNotifyRule notifyRule, int schemaVersion) implements OutboxJob {
    }

    record ControlJob(long id, UUID jobId, ChatControlType type, long userId, Long memberVersion,
            ChatControlReason reason, int schemaVersion) implements OutboxJob {
    }

    /**
     * 선점한 행.
     *
     * @param attemptCount 이번 선점을 포함한 적재 시도 횟수
     */
    record ClaimedJob(OutboxJob job, UUID claimToken, int attemptCount) {
    }

    /**
     * 재구성 대상. 적재했지만 그룹의 발행 체크포인트가 아직 PENDING인 작업.
     *
     * @param checkpointId  키셋 탐색 위치
     * @param generation    체크포인트의 처리 세대. 재적재 상한 격리를 이 세대 조건으로 기록한다
     * @param streamEntryId 마지막으로 적재한 항목 위치
     * @param requeueCount  이번 처리 세대에서 다시 적재한 횟수
     */
    record RequeueCandidate(long checkpointId, int generation, String streamEntryId, int requeueCount,
            OutboxJob job) {
    }

    /**
     * 처리권이 만료된 PROCESSING 행을 되돌린다. 적재 시도 상한에 닿았으면 DEAD로 격리한다.
     *
     * @return 되돌린 행 수
     */
    int releaseExpiredLeases(ChatJobStream stream, LocalDateTime now, int limit, int maxAttempts);

    /**
     * 실행할 때가 된 PENDING 행을 선점한다(PROCESSING, 시도 횟수 +1). 다른 relay가 잠근 행은 건너뛴다.
     *
     * @return 선점한 행(ID 순)
     */
    List<ClaimedJob> claim(ChatJobStream stream, UUID claimToken, LocalDateTime now, LocalDateTime leaseUntil,
            int limit);

    /**
     * 큐 연결 불가로 적재하지 못한 선점을 시도 횟수 없이 되돌린다(PENDING, 이번 선점이 올린 시도 횟수를 되돌림).
     * 처리권이 그대로일 때만 되돌린다.
     *
     * @return 되돌렸으면 true
     */
    boolean releaseClaim(ChatJobStream stream, long id, UUID claimToken, String error, LocalDateTime now);

    /**
     * 적재 성공을 기록한다.
     *
     * @return 기록했으면 true. 처리권이 만료·교체됐으면 false(늦은 결과)
     */
    boolean markEnqueued(ChatJobStream stream, long id, UUID claimToken, String streamEntryId, LocalDateTime now);

    /**
     * 적재 실패를 기록한다. 시도 상한에 닿았으면 DEAD, 아니면 다음 시도 시각을 정해 PENDING으로 돌린다.
     *
     * @return 바뀐 상태. 처리권이 만료·교체됐으면 빈 값(늦은 결과)
     */
    Optional<ChatOutboxStatus> markEnqueueFailed(ChatJobStream stream, long id, UUID claimToken, String error,
            LocalDateTime nextAttemptAt, int maxAttempts, LocalDateTime now);

    /** 안정 ID로 outbox 행을 찾는다 */
    Optional<OutboxJob> findJob(ChatJobStream stream, UUID jobId);

    /**
     * 재구성 대상을 체크포인트 ID 키셋으로 찾는다. 적재한 지 {@code enqueuedBefore}보다 오래됐고 cooldown이 지난 작업이다.
     * 재적재 상한에 닿은 작업도 포함한다(호출한 쪽이 격리한다).
     */
    List<RequeueCandidate> findRequeueCandidates(ChatJobStream stream, long afterCheckpointId,
            LocalDateTime enqueuedBefore, LocalDateTime cooldownBefore, int limit);

    /** 본문이 사라진 항목 위치로 재구성 대상을 찾는다(적재 시각 기준 없음, cooldown은 지킨다) */
    List<RequeueCandidate> findRequeueCandidatesByEntryIds(ChatJobStream stream, Collection<String> streamEntryIds,
            LocalDateTime cooldownBefore);

    /**
     * 재적재 처리권을 얻는다(재적재 횟수 +1, 재적재 시각 기록). cooldown·상한을 다시 확인한다.
     *
     * @return 얻었으면 true
     */
    boolean claimRequeue(ChatJobStream stream, long id, LocalDateTime cooldownBefore, int maxRequeues,
            LocalDateTime now);

    /**
     * 큐 연결 불가로 다시 적재하지 못한 재적재 처리권을 횟수 없이 되돌린다. 이 재구성이 얻은 처리권일 때만 되돌린다.
     *
     * @return 되돌렸으면 true
     */
    boolean releaseRequeueClaim(ChatJobStream stream, long id, LocalDateTime requeuedAt, LocalDateTime now);

    /** 재적재한 항목 위치를 기록한다. 이 재구성이 얻은 처리권({@code requeuedAt})이 그대로일 때만 기록한다 */
    boolean recordRequeued(ChatJobStream stream, long id, LocalDateTime requeuedAt, String streamEntryId,
            LocalDateTime now);

    /**
     * 운영 재처리 트랜잭션에서 outbox 행을 잠근다. 잠금 순서는 outbox 행 → 체크포인트 행이다.
     *
     * @return 행이 있으면 true
     */
    boolean lockJob(ChatJobStream stream, UUID jobId);

    /**
     * 운영 재처리 감사 기록. outbox나 그룹의 발행 체크포인트가 격리(DEAD)일 때만 비우기 직전 상태를 한 행으로 남긴다.
     *
     * @param requestedBy 재처리를 요청한 관리자 회원 ID. 없으면 null
     * @return 남긴 행 수. 0이면 재처리할 격리 상태가 아니다
     */
    int insertReprocessAudit(ChatJobStream stream, UUID jobId, Long requestedBy, LocalDateTime now);

    /**
     * 격리한 작업 목록(관리자). 발행 체크포인트가 DEAD이거나 outbox가 적재 격리(DEAD)인 작업을 체크포인트 ID 내림차순으로
     * 키셋 조회한다. outbox 행이 없는 격리(형식 오류·원본 누락으로 새로 만든 체크포인트)도 포함한다.
     *
     * @param beforeCheckpointId 이 체크포인트 ID보다 작은 것부터. 처음이면 null
     */
    List<ChatIsolatedJob> findIsolatedJobs(ChatJobStream stream, Long beforeCheckpointId, int limit);

    /**
     * 운영 재처리: 적재 시도 상한으로 격리한(DEAD) 행을 PENDING으로 돌린다. 이번 세대의 횟수·현재 오류는 비우고 누적 횟수는 남긴다.
     *
     * @return 되돌렸으면 true
     */
    boolean reopenDead(ChatJobStream stream, UUID jobId, LocalDateTime now);

    /**
     * 운영 재처리: 적재한(ENQUEUED) 행의 재적재 횟수·cooldown을 비운다.
     *
     * @return 비웠으면 true
     */
    boolean resetRequeueForReprocess(ChatJobStream stream, UUID jobId, LocalDateTime now);
}
