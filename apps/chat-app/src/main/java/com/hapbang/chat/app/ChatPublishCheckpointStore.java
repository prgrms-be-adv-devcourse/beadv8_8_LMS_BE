package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * consumer group별 Pub/Sub 발행 체크포인트 저장 계약. 사용자별 수신 이력이 아니다.
 * 상태 변경은 읽을 때의 처리 세대({@code generation})와 PENDING을 조건으로 건다. 옛 세대·다른 worker의 늦은 결과는
 * 새 상태를 덮지 않는다.
 */
public interface ChatPublishCheckpointStore {

    /**
     * @param nextAttemptAt 다음 발행 시도 시각. 실패한 적이 없으면 null
     */
    record CheckpointState(long id, ChatPublishStatus status, int generation, int attemptCount,
            LocalDateTime nextAttemptAt) {

        public boolean isDone() {
            return status != ChatPublishStatus.PENDING;
        }
    }

    /**
     * 발행 실패 결과. 시도 상한에 닿았으면 같은 기록에서 DEAD로 바꿨다.
     *
     * @param attemptCount 이번 처리 세대의 실패 횟수
     */
    record FailureResult(int attemptCount, ChatPublishStatus status) {
    }

    Optional<CheckpointState> find(String group, UUID jobId);

    /**
     * 발행 성공을 기록한다. 현재 오류는 비운다.
     *
     * @param subscriberCount 발행 명령 응답의 구독자 수(관측용)
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    boolean markPublished(String group, UUID jobId, int generation, long subscriberCount, LocalDateTime now);

    /**
     * 발행 실패를 기록하고, 시도 상한에 닿았으면 같은 기록에서 격리(DEAD, {@code PUBLISH_RETRY_EXHAUSTED})까지 한다.
     *
     * @param nextAttemptAt   다음 시도 시각. 없으면 null
     * @param subscriberCount 구독자 0명이면 0, 발행 명령이 실패했으면 null
     * @param streamEntryId   격리할 때 남길 원본 항목 위치
     * @return 기록 결과. 이미 완료됐거나 세대가 바뀌었으면 빈 값
     */
    Optional<FailureResult> recordFailureOrIsolate(String group, UUID jobId, int generation, String error,
            LocalDateTime nextAttemptAt, Long subscriberCount, int maxAttempts, String streamEntryId,
            LocalDateTime now);

    /**
     * 인프라 장애(Redis 연결 불가·자기 구독 단절)로 발행하지 못했음을 기록한다. 시도 횟수·다음 시도 시각은 바꾸지 않는다.
     *
     * @param subscriberCount 구독자 0명이면 0, 아니면 null
     * @return 기록했으면 true
     */
    boolean recordPaused(String group, UUID jobId, int generation, String error, Long subscriberCount,
            LocalDateTime now);

    /**
     * 격리(DEAD)를 기록한다. 원인과 원본 항목 위치를 남긴다.
     *
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    boolean markDead(String group, UUID jobId, int generation, ChatDeadReason reason, String streamEntryId,
            String error, LocalDateTime now);

    /** 체크포인트가 없는 작업(원본 누락·해석할 수 없는 항목)을 격리 행으로 남긴다. 이미 있으면 그대로 둔다 */
    void insertDeadIfAbsent(String group, UUID jobId, ChatDeadReason reason, String streamEntryId, String error,
            LocalDateTime now);

    /**
     * 운영 재처리 트랜잭션에서 체크포인트 행을 잠근다. outbox 행을 먼저 잠근 뒤 부른다.
     *
     * @return 행이 있으면 true
     */
    boolean lock(String group, UUID jobId);

    /**
     * 운영 재처리(발행 격리): DEAD 체크포인트의 처리 세대를 올려 PENDING으로 되돌린다.
     *
     * @return 되돌렸으면 true
     */
    boolean reopenDead(String group, UUID jobId, LocalDateTime now);

    /**
     * 운영 재처리(적재 격리): DEAD 또는 PENDING 체크포인트의 처리 세대를 올려 PENDING으로 만든다. PUBLISHED는 바꾸지 않는다.
     *
     * @return 세대를 올렸으면 true
     */
    boolean reopenUnpublished(String group, UUID jobId, LocalDateTime now);
}
