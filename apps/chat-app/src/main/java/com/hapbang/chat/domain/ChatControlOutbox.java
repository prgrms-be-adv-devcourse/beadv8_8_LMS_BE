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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 연결 제어 outbox. 기록은 {@code JdbcChatConnectionControl}이 회원 반영과 같은 트랜잭션에서 하고,
 * 적재 상태는 relay가 조건부 UPDATE로 바꾼다. 이 엔티티는 조회·검증용 매핑이며 직접 만들지 않는다.
 */
@Entity
@Table(name = "chat_control_outbox", schema = "chats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatControlOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID controlId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ChatControlType controlType;

    @Column(nullable = false)
    private Long userId;

    private Long memberVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ChatControlReason reason;

    @Column(nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    private UUID claimToken;

    private LocalDateTime leaseUntil;

    @Column(length = 64)
    private String streamEntryId;

    private LocalDateTime enqueuedAt;

    @Column(nullable = false)
    private int requeueCount;

    /** 재처리로 비우지 않는 누적 적재 시도 횟수 */
    @Column(nullable = false)
    private int totalAttemptCount;

    /** 재처리로 비우지 않는 누적 재적재 횟수 */
    @Column(nullable = false)
    private int totalRequeueCount;

    private LocalDateTime lastRequeuedAt;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
