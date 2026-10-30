package com.hapbang.chat.out;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatOutboxStore;
import com.hapbang.chat.app.dto.ChatIsolatedJob;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatDeliveryType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishStatus;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatOutboxStore}의 PostgreSQL 구현. 전달·제어 outbox의 적재 상태를 바꾼다. 두 테이블은 적재 공통 컬럼이 같아 같은 SQL을 쓴다.
 * <ul>
 *     <li>모든 변경은 한 문장의 조건부 UPDATE다. 호출한 쪽이 트랜잭션을 열지 않으면 문장마다 바로 커밋된다.
 *     선점({@link #claim})이 커밋된 뒤에 Redis에 적재하므로 Redis I/O 동안 행 잠금을 잡지 않는다.</li>
 *     <li>적재 결과는 처리권({@code claimToken})이 그대로일 때만 기록한다. 늦게 도착한 옛 relay의 결과는 버린다.</li>
 *     <li>발행 체크포인트는 건드리지 않는다. relay 결과가 consumer의 진행을 덮지 않는다.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class JdbcChatOutboxStore implements ChatOutboxStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbcClient;

    /**
     * 처리권이 만료된 PROCESSING 행을 되돌린다. 적재 시도 상한에 닿았으면 DEAD로 격리한다.
     *
     * @return 되돌린 행 수
     */
    @Override
    public int releaseExpiredLeases(ChatJobStream stream, LocalDateTime now, int limit, int maxAttempts) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        update %1$s o
                        set status = case when o.attempt_count >= :maxAttempts then 'DEAD' else 'PENDING' end,
                            claim_token = null, lease_until = null, next_attempt_at = :now,
                            last_error = '처리권 만료(relay 중단)', updated_at = :now
                        where o.id in (select id from %1$s
                                       where status = 'PROCESSING' and lease_until < :now
                                       order by lease_until
                                       limit :limit
                                       for update skip locked)
                        """.formatted(table.name))
                .param("maxAttempts", maxAttempts)
                .param("now", now)
                .param("limit", limit)
                .update();
    }

    /**
     * 실행할 때가 된 PENDING 행을 선점한다(PROCESSING, 시도 횟수 +1). 다른 relay가 잠근 행은 건너뛴다.
     *
     * @return 선점한 행(ID 순)
     */
    @Override
    public List<ClaimedJob> claim(ChatJobStream stream, UUID claimToken, LocalDateTime now, LocalDateTime leaseUntil,
            int limit) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        update %1$s o
                        set status = 'PROCESSING', claim_token = :claimToken, lease_until = :leaseUntil,
                            attempt_count = o.attempt_count + 1, total_attempt_count = o.total_attempt_count + 1,
                            updated_at = :now
                        where o.id in (select id from %1$s
                                       where status = 'PENDING' and next_attempt_at <= :now
                                       order by next_attempt_at, id
                                       limit :limit
                                       for update skip locked)
                        returning %2$s, o.attempt_count
                        """.formatted(table.name, table.jobColumns))
                .param("claimToken", claimToken)
                .param("leaseUntil", leaseUntil)
                .param("now", now)
                .param("limit", limit)
                .query((rs, rowNum) -> new ClaimedJob(table.mapper.mapRow(rs, rowNum), claimToken,
                        rs.getInt("attempt_count")))
                .list()
                .stream()
                .sorted(Comparator.comparingLong(claimed -> claimed.job().id()))
                .toList();
    }

    @Override
    public boolean releaseClaim(ChatJobStream stream, long id, UUID claimToken, String error, LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set status = 'PENDING', attempt_count = greatest(attempt_count - 1, 0),
                            total_attempt_count = greatest(total_attempt_count - 1, 0),
                            claim_token = null, lease_until = null, next_attempt_at = :now, last_error = :error,
                            updated_at = :now
                        where id = :id and status = 'PROCESSING' and claim_token = :claimToken
                        """.formatted(Table.of(stream).name))
                .param("now", now)
                .param("error", truncate(error))
                .param("id", id)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    @Override
    public boolean releaseRequeueClaim(ChatJobStream stream, long id, LocalDateTime requeuedAt, LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set requeue_count = greatest(requeue_count - 1, 0),
                            total_requeue_count = greatest(total_requeue_count - 1, 0),
                            last_requeued_at = null, updated_at = :now
                        where id = :id and status = 'ENQUEUED' and last_requeued_at = :requeuedAt
                        """.formatted(Table.of(stream).name))
                .param("now", now)
                .param("id", id)
                .param("requeuedAt", requeuedAt)
                .update() == 1;
    }

    /**
     * 적재 성공을 기록한다. 처리권이 그대로일 때만 기록한다.
     *
     * @return 기록했으면 true. 처리권이 만료·교체됐으면 false(늦은 결과)
     */
    @Override
    public boolean markEnqueued(ChatJobStream stream, long id, UUID claimToken, String streamEntryId,
            LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set status = 'ENQUEUED', stream_entry_id = :entryId, enqueued_at = :now,
                            claim_token = null, lease_until = null, last_error = null, updated_at = :now
                        where id = :id and status = 'PROCESSING' and claim_token = :claimToken
                        """.formatted(Table.of(stream).name))
                .param("entryId", streamEntryId)
                .param("now", now)
                .param("id", id)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    /**
     * 적재 실패를 기록한다. 시도 상한에 닿았으면 DEAD, 아니면 다음 시도 시각을 정해 PENDING으로 돌린다.
     *
     * @return 바뀐 상태. 처리권이 만료·교체됐으면 빈 값(늦은 결과)
     */
    @Override
    public Optional<ChatOutboxStatus> markEnqueueFailed(ChatJobStream stream, long id, UUID claimToken, String error,
            LocalDateTime nextAttemptAt, int maxAttempts, LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set status = case when attempt_count >= :maxAttempts then 'DEAD' else 'PENDING' end,
                            next_attempt_at = :nextAttemptAt, last_error = :error,
                            claim_token = null, lease_until = null, updated_at = :now
                        where id = :id and status = 'PROCESSING' and claim_token = :claimToken
                        returning status
                        """.formatted(Table.of(stream).name))
                .param("maxAttempts", maxAttempts)
                .param("nextAttemptAt", nextAttemptAt)
                .param("error", truncate(error))
                .param("now", now)
                .param("id", id)
                .param("claimToken", claimToken)
                .query((rs, rowNum) -> ChatOutboxStatus.valueOf(rs.getString("status")))
                .optional();
    }

    /** 안정 ID로 outbox 행을 찾는다 */
    @Override
    public Optional<OutboxJob> findJob(ChatJobStream stream, UUID jobId) {
        Table table = Table.of(stream);
        return jdbcClient.sql("select %s from %s o where o.%s = :jobId".formatted(table.jobColumns, table.name,
                        table.jobColumn))
                .param("jobId", jobId)
                .query(table.mapper)
                .optional();
    }

    /**
     * 재구성 대상을 체크포인트 ID 키셋으로 찾는다. 적재한 지 {@code enqueuedBefore}보다 오래됐고 cooldown이 지난 작업을
     * 고른다. 재적재 상한에 닿은 작업도 포함한다(호출한 쪽이 격리한다). 발행을 끝낸(PUBLISHED)·격리한(DEAD) 작업은
     * 대상이 아니다.
     */
    @Override
    public List<RequeueCandidate> findRequeueCandidates(ChatJobStream stream, long afterCheckpointId,
            LocalDateTime enqueuedBefore, LocalDateTime cooldownBefore, int limit) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        select c.id as checkpoint_id, c.generation, o.stream_entry_id, o.requeue_count, %2$s
                        from chats.chat_publish_checkpoint c
                                 join %1$s o on o.%3$s = c.job_id
                        where c.consumer_group = :group and c.status = 'PENDING' and c.id > :afterId
                          and o.status = 'ENQUEUED' and o.enqueued_at < :enqueuedBefore
                          and (o.last_requeued_at is null or o.last_requeued_at < :cooldownBefore)
                        order by c.id
                        limit :limit
                        """.formatted(table.name, table.jobColumns, table.jobColumn))
                .param("group", stream.consumerGroup())
                .param("afterId", afterCheckpointId)
                .param("enqueuedBefore", enqueuedBefore)
                .param("cooldownBefore", cooldownBefore)
                .param("limit", limit)
                .query(candidateMapper(table))
                .list();
    }

    /**
     * 본문이 사라진 항목 위치로 재구성 대상을 찾는다(회수 중 발견). 적재 시각 기준은 보지 않고 cooldown은 지킨다.
     * 재적재 상한에 닿은 작업도 포함한다(호출한 쪽이 격리한다).
     */
    @Override
    public List<RequeueCandidate> findRequeueCandidatesByEntryIds(ChatJobStream stream,
            Collection<String> streamEntryIds, LocalDateTime cooldownBefore) {
        if (streamEntryIds.isEmpty()) {
            return List.of();
        }
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        select c.id as checkpoint_id, c.generation, o.stream_entry_id, o.requeue_count, %2$s
                        from chats.chat_publish_checkpoint c
                                 join %1$s o on o.%3$s = c.job_id
                        where c.consumer_group = :group and c.status = 'PENDING'
                          and o.status = 'ENQUEUED' and o.stream_entry_id in (:entryIds)
                          and (o.last_requeued_at is null or o.last_requeued_at < :cooldownBefore)
                        order by c.id
                        """.formatted(table.name, table.jobColumns, table.jobColumn))
                .param("group", stream.consumerGroup())
                .param("entryIds", streamEntryIds)
                .param("cooldownBefore", cooldownBefore)
                .query(candidateMapper(table))
                .list();
    }

    /**
     * 재적재 처리권을 얻는다(재적재 횟수 +1, 재적재 시각 기록). cooldown·상한을 다시 확인해 다른 재구성과 겹치지 않게 한다.
     *
     * @return 얻었으면 true
     */
    @Override
    public boolean claimRequeue(ChatJobStream stream, long id, LocalDateTime cooldownBefore, int maxRequeues,
            LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set requeue_count = requeue_count + 1, total_requeue_count = total_requeue_count + 1,
                            last_requeued_at = :now, updated_at = :now
                        where id = :id and status = 'ENQUEUED'
                          and (last_requeued_at is null or last_requeued_at < :cooldownBefore)
                          and requeue_count < :maxRequeues
                        """.formatted(Table.of(stream).name))
                .param("now", now)
                .param("id", id)
                .param("cooldownBefore", cooldownBefore)
                .param("maxRequeues", maxRequeues)
                .update() == 1;
    }

    /**
     * 재적재한 항목 위치를 기록한다. 이 재구성이 얻은 처리권({@code requeuedAt})이 그대로일 때만 기록한다.
     */
    @Override
    public boolean recordRequeued(ChatJobStream stream, long id, LocalDateTime requeuedAt, String streamEntryId,
            LocalDateTime now) {
        return jdbcClient.sql("""
                        update %s
                        set stream_entry_id = :entryId, enqueued_at = :now, updated_at = :now
                        where id = :id and status = 'ENQUEUED' and last_requeued_at = :requeuedAt
                        """.formatted(Table.of(stream).name))
                .param("entryId", streamEntryId)
                .param("now", now)
                .param("id", id)
                .param("requeuedAt", requeuedAt)
                .update() == 1;
    }

    /**
     * 운영 재처리: 적재 시도 상한으로 격리한(DEAD) 행을 PENDING으로 돌려 relay가 다시 적재하게 한다.
     * 이번 세대의 적재 시도·재적재 횟수와 현재 오류는 비우고, 누적 횟수({@code total_*})는 남긴다.
     * 비우기 전 상태는 같은 트랜잭션에서 {@link #insertReprocessAudit}로 남긴다.
     *
     * @return 되돌렸으면 true. DEAD가 아니면 false
     */
    @Override
    public boolean reopenDead(ChatJobStream stream, UUID jobId, LocalDateTime now) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        update %s
                        set status = 'PENDING', next_attempt_at = :now, claim_token = null, lease_until = null,
                            last_error = null, attempt_count = 0, requeue_count = 0, last_requeued_at = null,
                            updated_at = :now
                        where %s = :jobId and status = 'DEAD'
                        """.formatted(table.name, table.jobColumn))
                .param("now", now)
                .param("jobId", jobId)
                .update() == 1;
    }

    /**
     * 운영 재처리 트랜잭션에서 outbox 행을 잠근다. 감사 기록·상태 초기화 전에 부르고, 이어서 체크포인트 행을 잠근다
     * ({@link JdbcChatPublishCheckpointStore#lock}). 잠근 뒤에는 다른 처리가 두 행을 바꿀 수 없어 감사 스냅숏과 초기화가
     * 같은 상태를 본다.
     *
     * @return 행이 있으면 true
     */
    @Override
    public boolean lockJob(ChatJobStream stream, UUID jobId) {
        Table table = Table.of(stream);
        return !jdbcClient.sql("select id from %s where %s = :jobId for update".formatted(table.name,
                        table.jobColumn))
                .param("jobId", jobId)
                .query(Long.class)
                .list()
                .isEmpty();
    }

    /**
     * 운영 재처리 감사 기록. outbox나 그룹의 발행 체크포인트가 격리(DEAD) 상태일 때만, 재처리로 비우기 직전의 상태를 한 행으로
     * 남긴다. 재처리와 같은 트랜잭션에서 {@link #lockJob}·체크포인트 잠금 뒤, 상태를 바꾸기 전에 부른다.
     *
     * @return 남긴 행 수. 0이면 재처리할 격리 상태가 아니다
     */
    @Override
    public int insertReprocessAudit(ChatJobStream stream, UUID jobId, Long requestedBy, LocalDateTime now) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        insert into chats.chat_delivery_reprocess_audit (job_stream, consumer_group, job_id,
                                previous_generation, previous_publish_status, previous_publish_attempt_count,
                                previous_dead_reason, previous_dead_stream_entry_id, previous_publish_error,
                                previous_outbox_status, previous_enqueue_attempt_count, previous_requeue_count,
                                previous_stream_entry_id, previous_enqueue_error, reprocessed_at, requested_by)
                        select :stream, :group, o.%2$s, c.generation, c.status, c.attempt_count, c.dead_reason,
                               c.dead_stream_entry_id, c.last_error, o.status, o.attempt_count, o.requeue_count,
                               o.stream_entry_id, o.last_error, :now, :requestedBy
                        from %1$s o
                                 left join chats.chat_publish_checkpoint c
                                           on c.consumer_group = :group and c.job_id = o.%2$s
                        where o.%2$s = :jobId and (o.status = 'DEAD' or c.status = 'DEAD')
                        """.formatted(table.name, table.jobColumn))
                .param("stream", stream.name())
                .param("group", stream.consumerGroup())
                .param("jobId", jobId)
                .param("now", now)
                .param("requestedBy", requestedBy, Types.BIGINT)
                .update();
    }

    @Override
    public List<ChatIsolatedJob> findIsolatedJobs(ChatJobStream stream, Long beforeCheckpointId, int limit) {
        Table table = Table.of(stream);
        // 두 격리(체크포인트 DEAD, outbox 적재 DEAD)를 각자의 부분 인덱스로 키셋 조회해 합친다
        return jdbcClient.sql("""
                        select x.* from (
                            (select c.id as checkpoint_id, c.job_id, c.status as publish_status, c.generation,
                                    c.attempt_count as publish_attempts, c.total_attempt_count as total_publish_attempts,
                                    c.dead_reason, c.dead_stream_entry_id, c.last_error as publish_error,
                                    c.dead_at, c.updated_at as checkpoint_updated_at,
                                    o.status as outbox_status, o.attempt_count as enqueue_attempts,
                                    o.total_attempt_count as total_enqueue_attempts, o.last_error as enqueue_error,
                                    o.updated_at as outbox_updated_at
                             from chats.chat_publish_checkpoint c
                                      left join %1$s o on o.%2$s = c.job_id
                             where c.consumer_group = :group and c.status = 'DEAD' and c.id < :before
                             order by c.id desc
                             limit :limit)
                            union
                            (select c.id, c.job_id, c.status, c.generation, c.attempt_count, c.total_attempt_count,
                                    c.dead_reason, c.dead_stream_entry_id, c.last_error, c.dead_at, c.updated_at,
                                    o.status, o.attempt_count, o.total_attempt_count, o.last_error, o.updated_at
                             from %1$s o
                                      join chats.chat_publish_checkpoint c
                                           on c.consumer_group = :group and c.job_id = o.%2$s
                             where o.status = 'DEAD' and c.id < :before
                             order by c.id desc
                             limit :limit)
                        ) x
                        order by x.checkpoint_id desc
                        limit :limit
                        """.formatted(table.name, table.jobColumn))
                .param("group", stream.consumerGroup())
                .param("before", beforeCheckpointId == null ? Long.MAX_VALUE : beforeCheckpointId)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    String outboxStatus = rs.getString("outbox_status");
                    Timestamp deadAt = rs.getTimestamp("dead_at");
                    Timestamp outboxUpdatedAt = rs.getTimestamp("outbox_updated_at");
                    Timestamp isolatedAt = deadAt != null ? deadAt
                            : "DEAD".equals(outboxStatus) ? outboxUpdatedAt : rs.getTimestamp("checkpoint_updated_at");
                    return new ChatIsolatedJob(stream, stream.consumerGroup(), rs.getObject("job_id", UUID.class),
                            rs.getLong("checkpoint_id"),
                            outboxStatus == null ? null : ChatOutboxStatus.valueOf(outboxStatus),
                            ChatPublishStatus.valueOf(rs.getString("publish_status")),
                            rs.getInt("generation"), rs.getString("dead_reason"),
                            rs.getString("dead_stream_entry_id"),
                            rs.getObject("enqueue_attempts", Integer.class),
                            rs.getObject("total_enqueue_attempts", Integer.class),
                            rs.getInt("publish_attempts"), rs.getInt("total_publish_attempts"),
                            rs.getString("enqueue_error"), rs.getString("publish_error"),
                            isolatedAt == null ? null : isolatedAt.toLocalDateTime(), outboxStatus != null);
                })
                .list();
    }

    /**
     * 운영 재처리: 적재한(ENQUEUED) 행의 재적재 횟수·cooldown을 비워 바로 다시 적재할 수 있게 한다.
     *
     * @return 비웠으면 true. ENQUEUED가 아니면 false
     */
    @Override
    public boolean resetRequeueForReprocess(ChatJobStream stream, UUID jobId, LocalDateTime now) {
        Table table = Table.of(stream);
        return jdbcClient.sql("""
                        update %s
                        set requeue_count = 0, last_requeued_at = null, updated_at = :now
                        where %s = :jobId and status = 'ENQUEUED'
                        """.formatted(table.name, table.jobColumn))
                .param("now", now)
                .param("jobId", jobId)
                .update() == 1;
    }

    private static RowMapper<RequeueCandidate> candidateMapper(Table table) {
        return (rs, rowNum) -> new RequeueCandidate(rs.getLong("checkpoint_id"), rs.getInt("generation"),
                rs.getString("stream_entry_id"), rs.getInt("requeue_count"), table.mapper.mapRow(rs, rowNum));
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    private static DeliveryJob deliveryJob(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryJob(rs.getLong("id"), rs.getObject("job_id", UUID.class),
                ChatDeliveryType.valueOf(rs.getString("delivery_type")), rs.getLong("message_id"),
                rs.getLong("chat_room_id"), rs.getLong("room_sequence"), rs.getLong("recipient_set_id"),
                ChatNotifyRule.valueOf(rs.getString("notify_rule")), rs.getInt("schema_version"));
    }

    private static ControlJob controlJob(ResultSet rs, int rowNum) throws SQLException {
        return new ControlJob(rs.getLong("id"), rs.getObject("job_id", UUID.class),
                ChatControlType.valueOf(rs.getString("control_type")), rs.getLong("user_id"),
                rs.getObject("member_version", Long.class), ChatControlReason.valueOf(rs.getString("reason")),
                rs.getInt("schema_version"));
    }

    /** 큐 종류별 테이블. 이름은 고정 값이라 SQL에 바로 넣는다 */
    private record Table(String name, String jobColumn, String jobColumns, RowMapper<OutboxJob> mapper) {

        private static final Table DELIVERY = new Table("chats.chat_outbox", "delivery_id", """
                o.id, o.delivery_id as job_id, o.delivery_type, o.message_id, o.chat_room_id, o.room_sequence,
                o.recipient_set_id, o.notify_rule, o.schema_version""", JdbcChatOutboxStore::deliveryJob);

        private static final Table CONTROL = new Table("chats.chat_control_outbox", "control_id", """
                o.id, o.control_id as job_id, o.control_type, o.user_id, o.member_version, o.reason,
                o.schema_version""", JdbcChatOutboxStore::controlJob);

        static Table of(ChatJobStream stream) {
            return switch (stream) {
                case DELIVERY -> DELIVERY;
                case CONTROL -> CONTROL;
            };
        }
    }
}
