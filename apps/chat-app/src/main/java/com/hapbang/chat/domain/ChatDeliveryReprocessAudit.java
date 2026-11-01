package com.hapbang.chat.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 운영 재처리 감사 기록. 재처리가 시도 횟수·격리 원인을 비우기 직전의 outbox·발행 체크포인트 상태를 재처리마다 한 행으로 남긴다.
 * {@code JpaChatOutboxStore}가 재처리와 같은 트랜잭션에서 INSERT만 한다. 이 엔티티는 조회·검증용 매핑이다.
 */
@Entity
@Table(name = "chat_delivery_reprocess_audit", schema = "chats",
        indexes = @Index(name = "idx_chat_delivery_reprocess_audit_job", columnList = "consumer_group, job_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatDeliveryReprocessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code DELIVERY} 또는 {@code CONTROL} */
    @Column(nullable = false, length = 20)
    private String jobStream;

    @Column(name = "consumer_group", nullable = false, length = 64)
    private String consumerGroup;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    /** 재처리 전 처리 세대. 체크포인트가 없었으면 null */
    private Integer previousGeneration;

    @Column(length = 20)
    private String previousPublishStatus;

    private Integer previousPublishAttemptCount;

    @Column(length = 40)
    private String previousDeadReason;

    @Column(length = 64)
    private String previousDeadStreamEntryId;

    @Column(length = 1000)
    private String previousPublishError;

    @Column(nullable = false, length = 20)
    private String previousOutboxStatus;

    @Column(nullable = false)
    private int previousEnqueueAttemptCount;

    @Column(nullable = false)
    private int previousRequeueCount;

    @Column(length = 64)
    private String previousStreamEntryId;

    @Column(length = 1000)
    private String previousEnqueueError;

    @Column(nullable = false)
    private LocalDateTime reprocessedAt;

    /** 재처리를 요청한 관리자 회원 ID. 관리자 API 밖의 재처리면 null */
    private Long requestedBy;
}
