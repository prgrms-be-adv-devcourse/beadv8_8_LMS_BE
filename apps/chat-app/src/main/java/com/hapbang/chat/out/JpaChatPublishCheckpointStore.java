package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.hapbang.chat.app.ChatPublishCheckpointStore;
import com.hapbang.chat.domain.ChatDeadReason;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatPublishCheckpointStore}의 JPA 구현. consumer group별 Pub/Sub 발행 체크포인트를 바꾼다. 문장은
 * {@link ChatPublishCheckpointRepository}의 native 쿼리다.
 * <ul>
 *     <li>모든 변경은 한 문장의 조건부 UPDATE다. 이 구현은 트랜잭션을 열지 않고, 변경 문장마다 repository 메서드의
 *     {@code @Transactional}(REQUIRED)을 따른다. 호출한 쪽이 트랜잭션을 열지 않으면 문장마다 바로 커밋되고, 열었으면 그
 *     트랜잭션에 참여한다.</li>
 *     <li>상태 변경은 읽을 때의 처리 세대({@code generation})와 PENDING을 조건으로 건다. 옛 세대·다른 worker의 늦은 결과는
 *     새 상태를 덮지 않는다.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class JpaChatPublishCheckpointStore implements ChatPublishCheckpointStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final ChatPublishCheckpointRepository chatPublishCheckpointRepository;


    @Override
    public Optional<CheckpointState> find(String group, UUID jobId) {
        return chatPublishCheckpointRepository.findState(group, jobId)
                .map(row -> new CheckpointState(row.getId(), row.getStatus(), row.getGeneration(),
                        row.getAttemptCount(), row.getNextAttemptAt()));
    }

    /**
     * 발행 성공을 기록한다. 현재 오류({@code last_error})는 비운다. 이전 실패는 누적 횟수와 재처리 감사 기록에 남는다.
     *
     * @param subscriberCount 발행 명령 응답의 구독자 수(관측용)
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    @Override
    public boolean markPublished(String group, UUID jobId, int generation, long subscriberCount, LocalDateTime now) {
        return chatPublishCheckpointRepository.markPublished(group, jobId, generation, subscriberCount, now) == 1;
    }

    /**
     * 운영 재처리 트랜잭션에서 체크포인트 행을 잠근다. 잠금 순서는 outbox 행 → 체크포인트 행이다
     * ({@link JpaChatOutboxStore#lockJob}을 먼저 부른다). 다른 경로는 한 문장에 한 행만 바꾸므로 이 순서와 엇갈리지 않는다.
     * 잠금은 호출한 트랜잭션이 끝날 때 풀린다.
     *
     * @return 행이 있으면 true
     */
    @Override
    public boolean lock(String group, UUID jobId) {
        return !chatPublishCheckpointRepository.lock(group, jobId).isEmpty();
    }


    /**
     * 발행 실패를 기록하고, 시도 상한에 닿았으면 같은 문장에서 격리(DEAD, {@code PUBLISH_RETRY_EXHAUSTED})까지 기록한다.
     * 실패 기록과 격리 사이에 종료돼 상한에 닿은 PENDING이 남는 경우가 없다.
     *
     * @param subscriberCount 구독자 0명이면 0, 발행 명령이 실패했으면 null
     * @param streamEntryId   격리할 때 남길 원본 항목 위치
     * @return 기록 결과. 이미 완료됐거나 세대가 바뀌었으면 빈 값
     */
    @Override
    public Optional<FailureResult> recordFailureOrIsolate(String group, UUID jobId, int generation, String error,
            LocalDateTime nextAttemptAt, Long subscriberCount, int maxAttempts, String streamEntryId,
            LocalDateTime now) {
        return chatPublishCheckpointRepository.recordFailureOrIsolate(group, jobId, generation, truncate(error),
                        nextAttemptAt, subscriberCount, maxAttempts, ChatDeadReason.PUBLISH_RETRY_EXHAUSTED.name(),
                        streamEntryId, now)
                .map(row -> new FailureResult(row.getAttemptCount(), row.getStatus()));
    }

    /**
     * 격리(DEAD)를 기록한다. 원인과 원본 항목 위치를 남긴다.
     *
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    @Override
    public boolean recordPaused(String group, UUID jobId, int generation, String error, Long subscriberCount,
            LocalDateTime now) {
        return chatPublishCheckpointRepository.recordPaused(group, jobId, generation, truncate(error),
                subscriberCount, now) == 1;
    }

    @Override
    public boolean markDead(String group, UUID jobId, int generation, ChatDeadReason reason, String streamEntryId,
            String error, LocalDateTime now) {
        return chatPublishCheckpointRepository.markDead(group, jobId, generation, reason.name(), streamEntryId,
                truncate(error), now) == 1;
    }

    /**
     * 체크포인트가 없는 작업(원본 누락·해석할 수 없는 항목)을 격리 행으로 남긴다. 이미 있으면 그대로 둔다.
     */
    @Override
    public void insertDeadIfAbsent(String group, UUID jobId, ChatDeadReason reason, String streamEntryId,
            String error, LocalDateTime now) {
        chatPublishCheckpointRepository.insertDeadIfAbsent(group, jobId, truncate(error), reason.name(),
                streamEntryId, now);
    }

    /**
     * 운영 재처리. 격리한 체크포인트의 처리 세대를 올려 PENDING으로 되돌린다. 이번 세대의 시도 횟수·격리 원인·현재 오류는
     * 비우고 누적 횟수({@code total_attempt_count})는 남긴다. 비우기 전 상태는 같은 트랜잭션의 재처리 감사 기록에 남긴다.
     *
     * @return 되돌렸으면 true
     */
    @Override
    public boolean reopenDead(String group, UUID jobId, LocalDateTime now) {
        return chatPublishCheckpointRepository.reopenDead(group, jobId, now) == 1;
    }

    /**
     * 운영 재처리(적재 격리): outbox가 적재 상한으로 격리돼 발행을 시도하지 못한 작업의 처리 세대를 올린다. 체크포인트는
     * 보통 PENDING이고, 옛 항목이 남아 DEAD가 됐을 수도 있다. 어느 쪽이든 새 세대의 PENDING으로 만들어, 옛 항목의 늦은 결과가
     * 새 처리를 덮지 않게 한다. 이미 PUBLISHED면 바꾸지 않는다.
     *
     * @return 세대를 올렸으면 true
     */
    @Override
    public boolean reopenUnpublished(String group, UUID jobId, LocalDateTime now) {
        return chatPublishCheckpointRepository.reopenUnpublished(group, jobId, now) == 1;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
