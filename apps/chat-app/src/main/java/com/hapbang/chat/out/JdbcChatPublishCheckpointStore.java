package com.hapbang.chat.out;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.hapbang.chat.app.ChatPublishCheckpointStore;
import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishStatus;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatPublishCheckpointStore}의 PostgreSQL 구현. consumer group별 Pub/Sub 발행 체크포인트를 바꾼다.
 * <ul>
 *     <li>모든 변경은 한 문장의 조건부 UPDATE다. 호출한 쪽이 트랜잭션을 열지 않으면 문장마다 바로 커밋된다.</li>
 *     <li>상태 변경은 읽을 때의 처리 세대({@code generation})와 PENDING을 조건으로 건다. 옛 세대·다른 worker의 늦은 결과는
 *     새 상태를 덮지 않는다.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class JdbcChatPublishCheckpointStore implements ChatPublishCheckpointStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbcClient;


    @Override
    public Optional<CheckpointState> find(String group, UUID jobId) {
        return jdbcClient.sql("""
                        select id, status, generation, attempt_count, next_attempt_at
                        from chats.chat_publish_checkpoint
                        where consumer_group = :group and job_id = :jobId
                        """)
                .param("group", group)
                .param("jobId", jobId)
                .query((rs, rowNum) -> {
                    Timestamp next = rs.getTimestamp("next_attempt_at");
                    return new CheckpointState(rs.getLong("id"), ChatPublishStatus.valueOf(rs.getString("status")),
                            rs.getInt("generation"), rs.getInt("attempt_count"),
                            next == null ? null : next.toLocalDateTime());
                })
                .optional();
    }

    /**
     * 발행 성공을 기록한다. 현재 오류({@code last_error})는 비운다. 이전 실패는 누적 횟수와 재처리 감사 기록에 남는다.
     *
     * @param subscriberCount 발행 명령 응답의 구독자 수(관측용)
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    @Override
    public boolean markPublished(String group, UUID jobId, int generation, long subscriberCount, LocalDateTime now) {
        return jdbcClient.sql("""
                        update chats.chat_publish_checkpoint
                        set status = 'PUBLISHED', published_at = :now, last_subscriber_count = :subscribers,
                            next_attempt_at = null, last_error = null, updated_at = :now
                        where consumer_group = :group and job_id = :jobId
                          and status = 'PENDING' and generation = :generation
                        """)
                .param("now", now)
                .param("subscribers", subscriberCount)
                .param("group", group)
                .param("jobId", jobId)
                .param("generation", generation)
                .update() == 1;
    }

    /**
     * 운영 재처리 트랜잭션에서 체크포인트 행을 잠근다. 잠금 순서는 outbox 행 → 체크포인트 행이다
     * ({@link JdbcChatOutboxStore#lockJob}을 먼저 부른다). 다른 경로는 한 문장에 한 행만 바꾸므로 이 순서와 엇갈리지 않는다.
     *
     * @return 행이 있으면 true
     */
    @Override
    public boolean lock(String group, UUID jobId) {
        return !jdbcClient.sql("""
                        select id from chats.chat_publish_checkpoint
                        where consumer_group = :group and job_id = :jobId
                        for update
                        """)
                .param("group", group)
                .param("jobId", jobId)
                .query(Long.class)
                .list()
                .isEmpty();
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
        return jdbcClient.sql("""
                        update chats.chat_publish_checkpoint
                        set attempt_count = attempt_count + 1, total_attempt_count = total_attempt_count + 1,
                            last_error = :error,
                            last_subscriber_count = coalesce(:subscribers, last_subscriber_count),
                            status = case when attempt_count + 1 >= :maxAttempts then 'DEAD' else status end,
                            next_attempt_at = case when attempt_count + 1 >= :maxAttempts then null
                                                   else cast(:nextAttemptAt as timestamp(6)) end,
                            dead_reason = case when attempt_count + 1 >= :maxAttempts
                                               then cast(:deadReason as varchar(40)) else dead_reason end,
                            dead_stream_entry_id = case when attempt_count + 1 >= :maxAttempts
                                                        then cast(:entryId as varchar(64))
                                                        else dead_stream_entry_id end,
                            dead_at = case when attempt_count + 1 >= :maxAttempts then cast(:now as timestamp(6))
                                           else dead_at end,
                            updated_at = :now
                        where consumer_group = :group and job_id = :jobId
                          and status = 'PENDING' and generation = :generation
                        returning attempt_count, status
                        """)
                .param("error", truncate(error))
                .param("subscribers", subscriberCount, Types.BIGINT)
                .param("maxAttempts", maxAttempts)
                // CASE 안의 파라미터는 SQL에서 형을 명시한다(null이어도 text로 추론되지 않게)
                .param("nextAttemptAt", nextAttemptAt, Types.TIMESTAMP)
                .param("deadReason", ChatDeadReason.PUBLISH_RETRY_EXHAUSTED.name())
                .param("entryId", streamEntryId)
                .param("now", now)
                .param("group", group)
                .param("jobId", jobId)
                .param("generation", generation)
                .query((rs, rowNum) -> new FailureResult(rs.getInt("attempt_count"),
                        ChatPublishStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * 격리(DEAD)를 기록한다. 원인과 원본 항목 위치를 남긴다.
     *
     * @return 기록했으면 true. 이미 완료됐거나 세대가 바뀌었으면 false
     */
    @Override
    public boolean recordPaused(String group, UUID jobId, int generation, String error, Long subscriberCount,
            LocalDateTime now) {
        return jdbcClient.sql("""
                        update chats.chat_publish_checkpoint
                        set last_error = :error,
                            last_subscriber_count = coalesce(:subscribers, last_subscriber_count),
                            updated_at = :now
                        where consumer_group = :group and job_id = :jobId
                          and status = 'PENDING' and generation = :generation
                        """)
                .param("error", truncate(error))
                .param("subscribers", subscriberCount, Types.BIGINT)
                .param("now", now)
                .param("group", group)
                .param("jobId", jobId)
                .param("generation", generation)
                .update() == 1;
    }

    @Override
    public boolean markDead(String group, UUID jobId, int generation, ChatDeadReason reason, String streamEntryId,
            String error, LocalDateTime now) {
        return jdbcClient.sql("""
                        update chats.chat_publish_checkpoint
                        set status = 'DEAD', dead_reason = :reason, dead_stream_entry_id = :entryId, dead_at = :now,
                            last_error = :error, next_attempt_at = null, updated_at = :now
                        where consumer_group = :group and job_id = :jobId
                          and status = 'PENDING' and generation = :generation
                        """)
                .param("reason", reason.name())
                .param("entryId", streamEntryId)
                .param("now", now)
                .param("error", truncate(error))
                .param("group", group)
                .param("jobId", jobId)
                .param("generation", generation)
                .update() == 1;
    }

    /**
     * 체크포인트가 없는 작업(원본 누락·해석할 수 없는 항목)을 격리 행으로 남긴다. 이미 있으면 그대로 둔다.
     */
    @Override
    public void insertDeadIfAbsent(String group, UUID jobId, ChatDeadReason reason, String streamEntryId,
            String error, LocalDateTime now) {
        jdbcClient.sql("""
                        insert into chats.chat_publish_checkpoint (consumer_group, job_id, status, generation,
                                attempt_count, last_error, dead_reason, dead_stream_entry_id, dead_at, created_at,
                                updated_at)
                        values (:group, :jobId, 'DEAD', 0, 0, :error, :reason, :entryId, :now, :now, :now)
                        on conflict (consumer_group, job_id) do nothing
                        """)
                .param("group", group)
                .param("jobId", jobId)
                .param("error", truncate(error))
                .param("reason", reason.name())
                .param("entryId", streamEntryId)
                .param("now", now)
                .update();
    }

    /**
     * 운영 재처리. 격리한 체크포인트의 처리 세대를 올려 PENDING으로 되돌린다. 이번 세대의 시도 횟수·격리 원인·현재 오류는
     * 비우고 누적 횟수({@code total_attempt_count})는 남긴다. 비우기 전 상태는 같은 트랜잭션의 재처리 감사 기록에 남긴다.
     *
     * @return 되돌렸으면 true
     */
    @Override
    public boolean reopenDead(String group, UUID jobId, LocalDateTime now) {
        return reopen(group, jobId, "status = 'DEAD'", now);
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
        return reopen(group, jobId, "status in ('DEAD', 'PENDING')", now);
    }

    private boolean reopen(String group, UUID jobId, String statusCondition, LocalDateTime now) {
        return jdbcClient.sql("""
                        update chats.chat_publish_checkpoint
                        set status = 'PENDING', generation = generation + 1, attempt_count = 0, next_attempt_at = null,
                            last_error = null,
                            dead_reason = null, dead_stream_entry_id = null, dead_at = null, updated_at = :now
                        where consumer_group = :group and job_id = :jobId and %s
                        """.formatted(statusCondition))
                .param("now", now)
                .param("group", group)
                .param("jobId", jobId)
                .update() == 1;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
