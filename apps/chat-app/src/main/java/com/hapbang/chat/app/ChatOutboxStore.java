package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatIsolatedJob;
import com.hapbang.chat.app.dto.ChatJobStream;
import com.hapbang.chat.app.port.ChatControlOutboxRepository;
import com.hapbang.chat.app.port.ChatOutboxRepository;
import com.hapbang.chat.app.port.ChatOutboxRows.Candidate;
import com.hapbang.chat.app.port.ChatOutboxRows.Claimed;
import com.hapbang.chat.app.port.ChatOutboxRows.ControlJobRow;
import com.hapbang.chat.app.port.ChatOutboxRows.DeliveryJobRow;
import com.hapbang.chat.app.port.ChatOutboxRows.IsolatedJobRow;
import com.hapbang.chat.app.port.ControlJob;
import com.hapbang.chat.app.port.DeliveryJob;
import com.hapbang.chat.app.port.OutboxJob;
import com.hapbang.chat.domain.ChatOutboxStatus;

import lombok.RequiredArgsConstructor;

/**
 * 전달·제어 outbox의 적재 상태를 바꾼다. relay·재구성·운영 재처리가 쓴다. 문장은 큐 종류별 repository의 native 쿼리다
 * (전달 {@link ChatOutboxRepository}, 제어 {@link ChatControlOutboxRepository}).
 * <ul>
 *     <li>모든 변경은 한 문장의 조건부 UPDATE다. 이 클래스는 트랜잭션을 열지 않고(클래스·메서드에 {@code @Transactional}을 두지
 *     않는다), 변경 문장마다 repository 메서드의 {@code @Transactional}(REQUIRED)을 따른다. 호출한 쪽이 트랜잭션을 열지 않으면
 *     문장마다 바로 커밋되고, 열었으면 그 트랜잭션에 참여한다. 선점({@link #claim})이 커밋된 뒤에 Redis에 적재하므로 Redis I/O
 *     동안 행 잠금을 잡지 않는다.</li>
 *     <li>적재 결과는 처리권({@code claimToken})이 그대로일 때만 기록한다. 늦게 도착한 옛 relay의 결과는 버린다.</li>
 *     <li>발행 체크포인트({@link ChatPublishCheckpointStore})는 건드리지 않는다. relay 결과가 consumer의 진행을 덮지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChatOutboxStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final ChatOutboxRepository chatOutboxRepository;
    private final ChatControlOutboxRepository chatControlOutboxRepository;

    /**
     * 선점한 행.
     *
     * @param attemptCount 이번 선점을 포함한 적재 시도 횟수
     */
    public record ClaimedJob(OutboxJob job, UUID claimToken, int attemptCount) {
    }

    /**
     * 재구성 대상. 적재했지만 그룹의 발행 체크포인트가 아직 PENDING인 작업.
     *
     * @param checkpointId  키셋 탐색 위치
     * @param generation    체크포인트의 처리 세대. 재적재 상한 격리를 이 세대 조건으로 기록한다
     * @param streamEntryId 마지막으로 적재한 항목 위치
     * @param requeueCount  이번 처리 세대에서 다시 적재한 횟수
     */
    public record RequeueCandidate(long checkpointId, int generation, String streamEntryId, int requeueCount,
            OutboxJob job) {
    }

    /**
     * 처리권이 만료된 PROCESSING 행을 되돌린다. 적재 시도 상한에 닿았으면 DEAD로 격리한다.
     *
     * @return 되돌린 행 수
     */
    public int releaseExpiredLeases(ChatJobStream stream, LocalDateTime now, int limit, int maxAttempts) {
        return switch (stream) {
            case DELIVERY -> chatOutboxRepository.releaseExpiredLeases(now, limit, maxAttempts);
            case CONTROL -> chatControlOutboxRepository.releaseExpiredLeases(now, limit, maxAttempts);
        };
    }

    /**
     * 실행할 때가 된 PENDING 행을 선점한다(PROCESSING, 시도 횟수 +1). 다른 relay가 잠근 행은 건너뛴다.
     *
     * @return 선점한 행(ID 순)
     */
    public List<ClaimedJob> claim(ChatJobStream stream, UUID claimToken, LocalDateTime now, LocalDateTime leaseUntil,
            int limit) {
        List<ClaimedJob> claimed = switch (stream) {
            case DELIVERY -> chatOutboxRepository.claim(claimToken, now, leaseUntil, limit).stream()
                    .map(row -> claimedJob(deliveryJob(row), claimToken, row))
                    .toList();
            case CONTROL -> chatControlOutboxRepository.claim(claimToken, now, leaseUntil, limit).stream()
                    .map(row -> claimedJob(controlJob(row), claimToken, row))
                    .toList();
        };
        return claimed.stream()
                .sorted(Comparator.comparingLong(job -> job.job().id()))
                .toList();
    }

    /**
     * 큐 연결 불가로 적재하지 못한 선점을 시도 횟수 없이 되돌린다(PENDING, 이번 선점이 올린 시도 횟수를 되돌림).
     * 처리권이 그대로일 때만 되돌린다.
     *
     * @return 되돌렸으면 true
     */
    public boolean releaseClaim(ChatJobStream stream, long id, UUID claimToken, String error, LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.releaseClaim(id, claimToken, truncate(error), now);
            case CONTROL -> chatControlOutboxRepository.releaseClaim(id, claimToken, truncate(error), now);
        };
        return updated == 1;
    }

    /**
     * 큐 연결 불가로 다시 적재하지 못한 재적재 처리권을 횟수 없이 되돌린다. 이 재구성이 얻은 처리권일 때만 되돌린다.
     *
     * @return 되돌렸으면 true
     */
    public boolean releaseRequeueClaim(ChatJobStream stream, long id, LocalDateTime requeuedAt, LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.releaseRequeueClaim(id, requeuedAt, now);
            case CONTROL -> chatControlOutboxRepository.releaseRequeueClaim(id, requeuedAt, now);
        };
        return updated == 1;
    }

    /**
     * 적재 성공을 기록한다. 처리권이 그대로일 때만 기록한다.
     *
     * @return 기록했으면 true. 처리권이 만료·교체됐으면 false(늦은 결과)
     */
    public boolean markEnqueued(ChatJobStream stream, long id, UUID claimToken, String streamEntryId,
            LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.markEnqueued(id, claimToken, streamEntryId, now);
            case CONTROL -> chatControlOutboxRepository.markEnqueued(id, claimToken, streamEntryId, now);
        };
        return updated == 1;
    }

    /**
     * 적재 실패를 기록한다. 시도 상한에 닿았으면 DEAD, 아니면 다음 시도 시각을 정해 PENDING으로 돌린다.
     *
     * @return 바뀐 상태. 처리권이 만료·교체됐으면 빈 값(늦은 결과)
     */
    public Optional<ChatOutboxStatus> markEnqueueFailed(ChatJobStream stream, long id, UUID claimToken, String error,
            LocalDateTime nextAttemptAt, int maxAttempts, LocalDateTime now) {
        Optional<String> status = switch (stream) {
            case DELIVERY -> chatOutboxRepository.markEnqueueFailed(id, claimToken, truncate(error), nextAttemptAt,
                    maxAttempts, now);
            case CONTROL -> chatControlOutboxRepository.markEnqueueFailed(id, claimToken, truncate(error),
                    nextAttemptAt, maxAttempts, now);
        };
        return status.map(ChatOutboxStatus::valueOf);
    }

    /** 안정 ID로 outbox 행을 찾는다 */
    public Optional<OutboxJob> findJob(ChatJobStream stream, UUID jobId) {
        return switch (stream) {
            case DELIVERY -> chatOutboxRepository.findJob(jobId).map(ChatOutboxStore::deliveryJob);
            case CONTROL -> chatControlOutboxRepository.findJob(jobId).map(ChatOutboxStore::controlJob);
        };
    }

    /**
     * 재구성 대상을 체크포인트 ID 키셋으로 찾는다. 적재한 지 {@code enqueuedBefore}보다 오래됐고 cooldown이 지난 작업을
     * 고른다. 재적재 상한에 닿은 작업도 포함한다(호출한 쪽이 격리한다). 발행을 끝낸(PUBLISHED)·격리한(DEAD) 작업은
     * 대상이 아니다.
     */
    public List<RequeueCandidate> findRequeueCandidates(ChatJobStream stream, long afterCheckpointId,
            LocalDateTime enqueuedBefore, LocalDateTime cooldownBefore, int limit) {
        String group = stream.consumerGroup();
        return switch (stream) {
            case DELIVERY -> chatOutboxRepository.findRequeueCandidates(group, afterCheckpointId, enqueuedBefore,
                            cooldownBefore, limit).stream()
                    .map(row -> candidate(row, deliveryJob(row)))
                    .toList();
            case CONTROL -> chatControlOutboxRepository.findRequeueCandidates(group, afterCheckpointId,
                            enqueuedBefore, cooldownBefore, limit).stream()
                    .map(row -> candidate(row, controlJob(row)))
                    .toList();
        };
    }

    /**
     * 본문이 사라진 항목 위치로 재구성 대상을 찾는다(회수 중 발견). 적재 시각 기준은 보지 않고 cooldown은 지킨다.
     * 재적재 상한에 닿은 작업도 포함한다(호출한 쪽이 격리한다).
     */
    public List<RequeueCandidate> findRequeueCandidatesByEntryIds(ChatJobStream stream,
            Collection<String> streamEntryIds, LocalDateTime cooldownBefore) {
        if (streamEntryIds.isEmpty()) {
            return List.of();
        }
        String group = stream.consumerGroup();
        return switch (stream) {
            case DELIVERY -> chatOutboxRepository.findRequeueCandidatesByEntryIds(group, streamEntryIds,
                            cooldownBefore).stream()
                    .map(row -> candidate(row, deliveryJob(row)))
                    .toList();
            case CONTROL -> chatControlOutboxRepository.findRequeueCandidatesByEntryIds(group, streamEntryIds,
                            cooldownBefore).stream()
                    .map(row -> candidate(row, controlJob(row)))
                    .toList();
        };
    }

    /**
     * 재적재 처리권을 얻는다(재적재 횟수 +1, 재적재 시각 기록). cooldown·상한을 다시 확인해 다른 재구성과 겹치지 않게 한다.
     *
     * @return 얻었으면 true
     */
    public boolean claimRequeue(ChatJobStream stream, long id, LocalDateTime cooldownBefore, int maxRequeues,
            LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.claimRequeue(id, cooldownBefore, maxRequeues, now);
            case CONTROL -> chatControlOutboxRepository.claimRequeue(id, cooldownBefore, maxRequeues, now);
        };
        return updated == 1;
    }

    /**
     * 재적재한 항목 위치를 기록한다. 이 재구성이 얻은 처리권({@code requeuedAt})이 그대로일 때만 기록한다.
     */
    public boolean recordRequeued(ChatJobStream stream, long id, LocalDateTime requeuedAt, String streamEntryId,
            LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.recordRequeued(id, requeuedAt, streamEntryId, now);
            case CONTROL -> chatControlOutboxRepository.recordRequeued(id, requeuedAt, streamEntryId, now);
        };
        return updated == 1;
    }

    /**
     * 운영 재처리: 적재 시도 상한으로 격리한(DEAD) 행을 PENDING으로 돌려 relay가 다시 적재하게 한다.
     * 이번 세대의 적재 시도·재적재 횟수와 현재 오류는 비우고, 누적 횟수({@code total_*})는 남긴다.
     * 비우기 전 상태는 같은 트랜잭션에서 {@link #insertReprocessAudit}로 남긴다.
     *
     * @return 되돌렸으면 true. DEAD가 아니면 false
     */
    public boolean reopenDead(ChatJobStream stream, UUID jobId, LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.reopenDead(jobId, now);
            case CONTROL -> chatControlOutboxRepository.reopenDead(jobId, now);
        };
        return updated == 1;
    }

    /**
     * 운영 재처리 트랜잭션에서 outbox 행을 잠근다. 감사 기록·상태 초기화 전에 부르고, 이어서 체크포인트 행을 잠근다
     * ({@link ChatPublishCheckpointStore#lock}). 잠근 뒤에는 다른 처리가 두 행을 바꿀 수 없어 감사 스냅숏과 초기화가
     * 같은 상태를 본다. 잠금은 호출한 트랜잭션이 끝날 때 풀린다.
     *
     * @return 행이 있으면 true
     */
    public boolean lockJob(ChatJobStream stream, UUID jobId) {
        List<Long> locked = switch (stream) {
            case DELIVERY -> chatOutboxRepository.lockJob(jobId);
            case CONTROL -> chatControlOutboxRepository.lockJob(jobId);
        };
        return !locked.isEmpty();
    }

    /**
     * 운영 재처리 감사 기록. outbox나 그룹의 발행 체크포인트가 격리(DEAD) 상태일 때만, 재처리로 비우기 직전의 상태를 한 행으로
     * 남긴다. 재처리와 같은 트랜잭션에서 {@link #lockJob}·체크포인트 잠금 뒤, 상태를 바꾸기 전에 부른다.
     *
     * @param requestedBy 재처리를 요청한 관리자 회원 ID. 없으면 null
     * @return 남긴 행 수. 0이면 재처리할 격리 상태가 아니다
     */
    public int insertReprocessAudit(ChatJobStream stream, UUID jobId, Long requestedBy, LocalDateTime now) {
        String group = stream.consumerGroup();
        return switch (stream) {
            case DELIVERY -> chatOutboxRepository.insertReprocessAudit(stream.name(), group, jobId, now,
                    requestedBy);
            case CONTROL -> chatControlOutboxRepository.insertReprocessAudit(stream.name(), group, jobId, now,
                    requestedBy);
        };
    }

    /**
     * 격리한 작업 목록(관리자). 발행 체크포인트가 DEAD이거나 outbox가 적재 격리(DEAD)인 작업을 체크포인트 ID 내림차순으로
     * 키셋 조회한다. outbox 행이 없는 격리(형식 오류·원본 누락으로 새로 만든 체크포인트)도 포함한다.
     *
     * @param beforeCheckpointId 이 체크포인트 ID보다 작은 것부터. 처음이면 null
     */
    public List<ChatIsolatedJob> findIsolatedJobs(ChatJobStream stream, Long beforeCheckpointId, int limit) {
        String group = stream.consumerGroup();
        long before = beforeCheckpointId == null ? Long.MAX_VALUE : beforeCheckpointId;
        List<IsolatedJobRow> rows = switch (stream) {
            case DELIVERY -> chatOutboxRepository.findIsolatedJobs(group, before, limit);
            case CONTROL -> chatControlOutboxRepository.findIsolatedJobs(group, before, limit);
        };
        return rows.stream()
                .map(row -> isolatedJob(stream, row))
                .toList();
    }

    /**
     * 운영 재처리: 적재한(ENQUEUED) 행의 재적재 횟수·cooldown을 비워 바로 다시 적재할 수 있게 한다.
     *
     * @return 비웠으면 true. ENQUEUED가 아니면 false
     */
    public boolean resetRequeueForReprocess(ChatJobStream stream, UUID jobId, LocalDateTime now) {
        int updated = switch (stream) {
            case DELIVERY -> chatOutboxRepository.resetRequeueForReprocess(jobId, now);
            case CONTROL -> chatControlOutboxRepository.resetRequeueForReprocess(jobId, now);
        };
        return updated == 1;
    }

    private static ClaimedJob claimedJob(OutboxJob job, UUID claimToken, Claimed row) {
        return new ClaimedJob(job, claimToken, row.getAttemptCount());
    }

    private static RequeueCandidate candidate(Candidate row, OutboxJob job) {
        return new RequeueCandidate(row.getCheckpointId(), row.getGeneration(), row.getStreamEntryId(),
                row.getRequeueCount(), job);
    }

    private static ChatIsolatedJob isolatedJob(ChatJobStream stream, IsolatedJobRow row) {
        ChatOutboxStatus outboxStatus = row.getOutboxStatus();
        LocalDateTime isolatedAt = row.getDeadAt() != null ? row.getDeadAt()
                : outboxStatus == ChatOutboxStatus.DEAD ? row.getOutboxUpdatedAt() : row.getCheckpointUpdatedAt();
        return new ChatIsolatedJob(stream, stream.consumerGroup(), row.getJobId(), row.getCheckpointId(),
                outboxStatus, row.getPublishStatus(), row.getGeneration(), row.getDeadReason(),
                row.getDeadStreamEntryId(), row.getEnqueueAttempts(), row.getTotalEnqueueAttempts(),
                row.getPublishAttempts(), row.getTotalPublishAttempts(), row.getEnqueueError(),
                row.getPublishError(), isolatedAt, outboxStatus != null);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private static DeliveryJob deliveryJob(DeliveryJobRow row) {
        return new DeliveryJob(row.getId(), row.getJobId(), row.getDeliveryType(), row.getMessageId(),
                row.getChatRoomId(), row.getRoomSequence(), row.getRecipientSetId(), row.getNotifyRule(),
                row.getSchemaVersion());
    }

    private static ControlJob controlJob(ControlJobRow row) {
        return new ControlJob(row.getId(), row.getJobId(), row.getControlType(), row.getUserId(),
                row.getMemberVersion(), row.getReason(), row.getLoggedOutAt(), row.getSchemaVersion());
    }
}
