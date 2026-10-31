package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * 발행 체크포인트 저장·조회. 상태 변경은 {@link JpaChatPublishCheckpointStore}가 부르는 아래 조건부 native 문장으로만 한다.
 * 변경 문장은 {@code @Transactional}(REQUIRED)이다. 호출한 쪽 트랜잭션이 없으면 문장마다 바로 커밋되고, 있으면 참여한다.
 * 변경 문장은 영속성 컨텍스트를 비우지 않는다. 호출한 쪽이 쓰는 엔티티를 떼어 내지 않는다.
 */
public interface ChatPublishCheckpointRepository extends JpaRepository<ChatPublishCheckpoint, Long> {

    /** 체크포인트 상태(native projection). 엔티티로 읽지 않아 늘 문장이 읽은 값을 쓴다 */
    interface StateRow {

        long getId();

        ChatPublishStatus getStatus();

        int getGeneration();

        int getAttemptCount();

        LocalDateTime getNextAttemptAt();
    }

    /** 발행 실패를 기록한 뒤의 상태 */
    interface FailureRow {

        int getAttemptCount();

        ChatPublishStatus getStatus();
    }

    Optional<ChatPublishCheckpoint> findByConsumerGroupAndJobId(String consumerGroup, UUID jobId);

    @Query(nativeQuery = true, value = """
            select id, status, generation, attempt_count, next_attempt_at
            from chats.chat_publish_checkpoint
            where consumer_group = :group and job_id = :jobId
            """)
    Optional<StateRow> findState(@Param("group") String group, @Param("jobId") UUID jobId);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_publish_checkpoint
            set status = 'PUBLISHED', published_at = :now, last_subscriber_count = :subscribers,
                next_attempt_at = null, last_error = null, updated_at = :now
            where consumer_group = :group and job_id = :jobId
              and status = 'PENDING' and generation = :generation
            """)
    int markPublished(@Param("group") String group, @Param("jobId") UUID jobId, @Param("generation") int generation,
            @Param("subscribers") long subscribers, @Param("now") LocalDateTime now);

    /** @return 잠근 행의 ID. 행이 없으면 빈 목록 */
    @Transactional
    @Query(nativeQuery = true, value = """
            select id from chats.chat_publish_checkpoint
            where consumer_group = :group and job_id = :jobId
            for update
            """)
    List<Long> lock(@Param("group") String group, @Param("jobId") UUID jobId);

    /** CASE 안의 파라미터는 SQL에서 형을 명시한다(null이어도 다른 형으로 추론되지 않게) */
    @Transactional
    @Query(nativeQuery = true, value = """
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
    Optional<FailureRow> recordFailureOrIsolate(@Param("group") String group, @Param("jobId") UUID jobId,
            @Param("generation") int generation, @Param("error") String error,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("subscribers") Long subscribers,
            @Param("maxAttempts") int maxAttempts, @Param("deadReason") String deadReason,
            @Param("entryId") String entryId, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_publish_checkpoint
            set last_error = :error,
                last_subscriber_count = coalesce(:subscribers, last_subscriber_count),
                updated_at = :now
            where consumer_group = :group and job_id = :jobId
              and status = 'PENDING' and generation = :generation
            """)
    int recordPaused(@Param("group") String group, @Param("jobId") UUID jobId, @Param("generation") int generation,
            @Param("error") String error, @Param("subscribers") Long subscribers, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_publish_checkpoint
            set status = 'DEAD', dead_reason = :reason, dead_stream_entry_id = :entryId, dead_at = :now,
                last_error = :error, next_attempt_at = null, updated_at = :now
            where consumer_group = :group and job_id = :jobId
              and status = 'PENDING' and generation = :generation
            """)
    int markDead(@Param("group") String group, @Param("jobId") UUID jobId, @Param("generation") int generation,
            @Param("reason") String reason, @Param("entryId") String entryId, @Param("error") String error,
            @Param("now") LocalDateTime now);

    /**
     * 체크포인트가 없을 때만 격리 행을 넣는다. 이미 있으면 유일 제약의 {@code ON CONFLICT DO NOTHING}으로 예외 없이
     * 건너뛴다.
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into chats.chat_publish_checkpoint (consumer_group, job_id, status, generation,
                    attempt_count, last_error, dead_reason, dead_stream_entry_id, dead_at, created_at,
                    updated_at)
            values (:group, :jobId, 'DEAD', 0, 0, :error, :reason, :entryId, :now, :now, :now)
            on conflict (consumer_group, job_id) do nothing
            """)
    int insertDeadIfAbsent(@Param("group") String group, @Param("jobId") UUID jobId, @Param("error") String error,
            @Param("reason") String reason, @Param("entryId") String entryId, @Param("now") LocalDateTime now);

    /** 격리(DEAD)한 체크포인트의 처리 세대를 올려 PENDING으로 되돌린다 */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_publish_checkpoint
            set status = 'PENDING', generation = generation + 1, attempt_count = 0, next_attempt_at = null,
                last_error = null,
                dead_reason = null, dead_stream_entry_id = null, dead_at = null, updated_at = :now
            where consumer_group = :group and job_id = :jobId and status = 'DEAD'
            """)
    int reopenDead(@Param("group") String group, @Param("jobId") UUID jobId, @Param("now") LocalDateTime now);

    /** 발행하지 않은(DEAD 또는 PENDING) 체크포인트의 처리 세대를 올려 PENDING으로 만든다 */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            update chats.chat_publish_checkpoint
            set status = 'PENDING', generation = generation + 1, attempt_count = 0, next_attempt_at = null,
                last_error = null,
                dead_reason = null, dead_stream_entry_id = null, dead_at = null, updated_at = :now
            where consumer_group = :group and job_id = :jobId and status in ('DEAD', 'PENDING')
            """)
    int reopenUnpublished(@Param("group") String group, @Param("jobId") UUID jobId,
            @Param("now") LocalDateTime now);
}
