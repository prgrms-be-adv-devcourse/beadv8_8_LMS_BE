package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.out.ChatOutboxRows.ClaimedDeliveryRow;
import com.hapbang.chat.out.ChatOutboxRows.DeliveryCandidateRow;
import com.hapbang.chat.out.ChatOutboxRows.DeliveryJobRow;
import com.hapbang.chat.out.ChatOutboxRows.IsolatedJobRow;

/**
 * 메시지 전달 outbox 저장·조회. 적재 상태 변경은 {@link JpaChatOutboxStore}가 부르는 아래 조건부 native 문장으로만 한다.
 * <ul>
 *     <li>제어 outbox({@link ChatControlOutboxRepository})와 적재 공통 컬럼이 같아 테이블만 바꾼 같은 문장을 둔다.</li>
 *     <li>변경 문장은 {@code @Transactional}(REQUIRED)이다. 호출한 쪽 트랜잭션이 없으면 문장마다 바로 커밋되고, 있으면
 *     참여한다. 조회 문장은 트랜잭션을 열지 않는다.</li>
 *     <li>변경 문장은 영속성 컨텍스트를 비우지 않는다. 호출한 쪽이 쓰는 엔티티를 떼어 내지 않는다.</li>
 * </ul>
 */
public interface ChatOutboxRepository extends JpaRepository<ChatOutbox, Long> {

    Optional<ChatOutbox> findByDeliveryId(UUID deliveryId);

    List<ChatOutbox> findByChatRoomIdOrderByIdAsc(Long chatRoomId);

    Optional<ChatOutbox> findByMessageId(Long messageId);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox o
            set status = case when o.attempt_count >= :maxAttempts then 'DEAD' else 'PENDING' end,
                claim_token = null, lease_until = null, next_attempt_at = :now,
                last_error = '처리권 만료(relay 중단)', updated_at = :now
            where o.id in (select id from chats.chat_outbox
                           where status = 'PROCESSING' and lease_until < :now
                           order by lease_until
                           limit :limit
                           for update skip locked)
            """)
    int releaseExpiredLeases(@Param("now") LocalDateTime now, @Param("limit") int limit,
            @Param("maxAttempts") int maxAttempts);

    @Transactional
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox o
            set status = 'PROCESSING', claim_token = :claimToken, lease_until = :leaseUntil,
                attempt_count = o.attempt_count + 1, total_attempt_count = o.total_attempt_count + 1,
                updated_at = :now
            where o.id in (select id from chats.chat_outbox
                           where status = 'PENDING' and next_attempt_at <= :now
                           order by next_attempt_at, id
                           limit :limit
                           for update skip locked)
            returning o.id, o.delivery_id as job_id, o.delivery_type, o.message_id, o.chat_room_id, o.room_sequence,
                      o.recipient_set_id, o.notify_rule, o.schema_version, o.attempt_count
            """)
    List<ClaimedDeliveryRow> claim(@Param("claimToken") UUID claimToken, @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil, @Param("limit") int limit);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set status = 'PENDING', attempt_count = greatest(attempt_count - 1, 0),
                total_attempt_count = greatest(total_attempt_count - 1, 0),
                claim_token = null, lease_until = null, next_attempt_at = :now, last_error = :error,
                updated_at = :now
            where id = :id and status = 'PROCESSING' and claim_token = :claimToken
            """)
    int releaseClaim(@Param("id") long id, @Param("claimToken") UUID claimToken, @Param("error") String error,
            @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set requeue_count = greatest(requeue_count - 1, 0),
                total_requeue_count = greatest(total_requeue_count - 1, 0),
                last_requeued_at = null, updated_at = :now
            where id = :id and status = 'ENQUEUED' and last_requeued_at = :requeuedAt
            """)
    int releaseRequeueClaim(@Param("id") long id, @Param("requeuedAt") LocalDateTime requeuedAt,
            @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set status = 'ENQUEUED', stream_entry_id = :entryId, enqueued_at = :now,
                claim_token = null, lease_until = null, last_error = null, updated_at = :now
            where id = :id and status = 'PROCESSING' and claim_token = :claimToken
            """)
    int markEnqueued(@Param("id") long id, @Param("claimToken") UUID claimToken, @Param("entryId") String entryId,
            @Param("now") LocalDateTime now);

    /** @return 바뀐 상태. 처리권이 그대로가 아니면 빈 값 */
    @Transactional
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set status = case when attempt_count >= :maxAttempts then 'DEAD' else 'PENDING' end,
                next_attempt_at = :nextAttemptAt, last_error = :error,
                claim_token = null, lease_until = null, updated_at = :now
            where id = :id and status = 'PROCESSING' and claim_token = :claimToken
            returning status
            """)
    Optional<String> markEnqueueFailed(@Param("id") long id, @Param("claimToken") UUID claimToken,
            @Param("error") String error, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now);

    @Query(nativeQuery = true, value = """
            select o.id, o.delivery_id as job_id, o.delivery_type, o.message_id, o.chat_room_id, o.room_sequence,
                   o.recipient_set_id, o.notify_rule, o.schema_version
            from chats.chat_outbox o
            where o.delivery_id = :jobId
            """)
    Optional<DeliveryJobRow> findJob(@Param("jobId") UUID jobId);

    @Query(nativeQuery = true, value = """
            select c.id as checkpoint_id, c.generation, o.stream_entry_id, o.requeue_count,
                   o.id, o.delivery_id as job_id, o.delivery_type, o.message_id, o.chat_room_id, o.room_sequence,
                   o.recipient_set_id, o.notify_rule, o.schema_version
            from chats.chat_publish_checkpoint c
                     join chats.chat_outbox o on o.delivery_id = c.job_id
            where c.consumer_group = :group and c.status = 'PENDING' and c.id > :afterId
              and o.status = 'ENQUEUED' and o.enqueued_at < :enqueuedBefore
              and (o.last_requeued_at is null or o.last_requeued_at < :cooldownBefore)
            order by c.id
            limit :limit
            """)
    List<DeliveryCandidateRow> findRequeueCandidates(@Param("group") String group, @Param("afterId") long afterId,
            @Param("enqueuedBefore") LocalDateTime enqueuedBefore,
            @Param("cooldownBefore") LocalDateTime cooldownBefore, @Param("limit") int limit);

    @Query(nativeQuery = true, value = """
            select c.id as checkpoint_id, c.generation, o.stream_entry_id, o.requeue_count,
                   o.id, o.delivery_id as job_id, o.delivery_type, o.message_id, o.chat_room_id, o.room_sequence,
                   o.recipient_set_id, o.notify_rule, o.schema_version
            from chats.chat_publish_checkpoint c
                     join chats.chat_outbox o on o.delivery_id = c.job_id
            where c.consumer_group = :group and c.status = 'PENDING'
              and o.status = 'ENQUEUED' and o.stream_entry_id in (:entryIds)
              and (o.last_requeued_at is null or o.last_requeued_at < :cooldownBefore)
            order by c.id
            """)
    List<DeliveryCandidateRow> findRequeueCandidatesByEntryIds(@Param("group") String group,
            @Param("entryIds") Collection<String> entryIds, @Param("cooldownBefore") LocalDateTime cooldownBefore);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set requeue_count = requeue_count + 1, total_requeue_count = total_requeue_count + 1,
                last_requeued_at = :now, updated_at = :now
            where id = :id and status = 'ENQUEUED'
              and (last_requeued_at is null or last_requeued_at < :cooldownBefore)
              and requeue_count < :maxRequeues
            """)
    int claimRequeue(@Param("id") long id, @Param("cooldownBefore") LocalDateTime cooldownBefore,
            @Param("maxRequeues") int maxRequeues, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set stream_entry_id = :entryId, enqueued_at = :now, updated_at = :now
            where id = :id and status = 'ENQUEUED' and last_requeued_at = :requeuedAt
            """)
    int recordRequeued(@Param("id") long id, @Param("requeuedAt") LocalDateTime requeuedAt,
            @Param("entryId") String entryId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set status = 'PENDING', next_attempt_at = :now, claim_token = null, lease_until = null,
                last_error = null, attempt_count = 0, requeue_count = 0, last_requeued_at = null,
                updated_at = :now
            where delivery_id = :jobId and status = 'DEAD'
            """)
    int reopenDead(@Param("jobId") UUID jobId, @Param("now") LocalDateTime now);

    /** @return 잠근 행의 ID. 행이 없으면 빈 목록 */
    @Transactional
    @Query(nativeQuery = true, value = "select id from chats.chat_outbox where delivery_id = :jobId for update")
    List<Long> lockJob(@Param("jobId") UUID jobId);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into chats.chat_delivery_reprocess_audit (job_stream, consumer_group, job_id,
                    previous_generation, previous_publish_status, previous_publish_attempt_count,
                    previous_dead_reason, previous_dead_stream_entry_id, previous_publish_error,
                    previous_outbox_status, previous_enqueue_attempt_count, previous_requeue_count,
                    previous_stream_entry_id, previous_enqueue_error, reprocessed_at, requested_by)
            select :stream, :group, o.delivery_id, c.generation, c.status, c.attempt_count, c.dead_reason,
                   c.dead_stream_entry_id, c.last_error, o.status, o.attempt_count, o.requeue_count,
                   o.stream_entry_id, o.last_error, :now, :requestedBy
            from chats.chat_outbox o
                     left join chats.chat_publish_checkpoint c
                               on c.consumer_group = :group and c.job_id = o.delivery_id
            where o.delivery_id = :jobId and (o.status = 'DEAD' or c.status = 'DEAD')
            """)
    int insertReprocessAudit(@Param("stream") String stream, @Param("group") String group,
            @Param("jobId") UUID jobId, @Param("now") LocalDateTime now, @Param("requestedBy") Long requestedBy);

    /** 두 격리(체크포인트 DEAD, outbox 적재 DEAD)를 각자의 부분 인덱스로 키셋 조회해 합친다 */
    @Query(nativeQuery = true, value = """
            select x.* from (
                (select c.id as checkpoint_id, c.job_id, c.status as publish_status, c.generation,
                        c.attempt_count as publish_attempts, c.total_attempt_count as total_publish_attempts,
                        c.dead_reason, c.dead_stream_entry_id, c.last_error as publish_error,
                        c.dead_at, c.updated_at as checkpoint_updated_at,
                        o.status as outbox_status, o.attempt_count as enqueue_attempts,
                        o.total_attempt_count as total_enqueue_attempts, o.last_error as enqueue_error,
                        o.updated_at as outbox_updated_at
                 from chats.chat_publish_checkpoint c
                          left join chats.chat_outbox o on o.delivery_id = c.job_id
                 where c.consumer_group = :group and c.status = 'DEAD' and c.id < :before
                 order by c.id desc
                 limit :limit)
                union
                (select c.id, c.job_id, c.status, c.generation, c.attempt_count, c.total_attempt_count,
                        c.dead_reason, c.dead_stream_entry_id, c.last_error, c.dead_at, c.updated_at,
                        o.status, o.attempt_count, o.total_attempt_count, o.last_error, o.updated_at
                 from chats.chat_outbox o
                          join chats.chat_publish_checkpoint c
                               on c.consumer_group = :group and c.job_id = o.delivery_id
                 where o.status = 'DEAD' and c.id < :before
                 order by c.id desc
                 limit :limit)
            ) x
            order by x.checkpoint_id desc
            limit :limit
            """)
    List<IsolatedJobRow> findIsolatedJobs(@Param("group") String group, @Param("before") long before,
            @Param("limit") int limit);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_outbox
            set requeue_count = 0, last_requeued_at = null, updated_at = :now
            where delivery_id = :jobId and status = 'ENQUEUED'
            """)
    int resetRequeueForReprocess(@Param("jobId") UUID jobId, @Param("now") LocalDateTime now);
}
