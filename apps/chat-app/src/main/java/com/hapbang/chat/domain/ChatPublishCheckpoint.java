package com.hapbang.chat.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * consumer group별 Pub/Sub 발행 체크포인트. 사용자별 수신 이력이 아니다.
 * outbox 행과 같은 트랜잭션에 {@link ChatPublishStatus#PENDING}으로 만들고, 이후 상태는 consumer가 처리 세대
 * ({@code generation})를 조건으로 건 UPDATE로만 바꾼다. 옛 세대의 결과는 새 상태를 덮지 않는다.
 */
@Entity
@Table(name = "chat_publish_checkpoint", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_publish_checkpoint_group_job",
                columnNames = {"consumer_group", "job_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatPublishCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_group", nullable = false, length = 64)
    private String consumerGroup;

    /** {@code deliveryId} 또는 {@code controlId} */
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatPublishStatus status;

    @Column(nullable = false)
    private int generation;

    /** 이번 처리 세대의 발행 실패 횟수(구독자 0명·Redis 연결 불가는 세지 않는다) */
    @Column(nullable = false)
    private int attemptCount;

    /** 재처리로 비우지 않는 누적 발행 실패 횟수 */
    @Column(nullable = false)
    private int totalAttemptCount;

    private LocalDateTime nextAttemptAt;

    @Column(length = 1000)
    private String lastError;

    private LocalDateTime publishedAt;

    private Long lastSubscriberCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ChatDeadReason deadReason;

    @Column(length = 64)
    private String deadStreamEntryId;

    private LocalDateTime deadAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private ChatPublishCheckpoint(String consumerGroup, UUID jobId, LocalDateTime now) {
        this.consumerGroup = consumerGroup;
        this.jobId = jobId;
        this.status = ChatPublishStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ChatPublishCheckpoint pending(String consumerGroup, UUID jobId, LocalDateTime now) {
        return new ChatPublishCheckpoint(consumerGroup, jobId, now);
    }
}
