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
 * 메시지 전달 outbox. 메시지 저장과 같은 트랜잭션에 {@link ChatOutboxStatus#PENDING}으로 만든다.
 * 적재 상태·처리권·재구성 기록은 relay가 조건부 UPDATE로만 바꾼다(엔티티로 상태를 고치지 않는다).
 * <ul>
 *     <li>{@code deliveryId}: 안정 ID. 재시도·재적재해도 같고, 발행 체크포인트의 작업 ID다.</li>
 *     <li>{@code recipientSetId}: 메시지 저장 때 확정한 불변 수신자 집합. 현재 참여자 목록으로 바꾸지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "chat_outbox", schema = "chats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatOutbox {

    /** Stream 항목 스키마 버전 */
    public static final int SCHEMA_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatDeliveryType deliveryType;

    @Column(nullable = false)
    private Long messageId;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(nullable = false)
    private long roomSequence;

    @Column(nullable = false)
    private Long recipientSetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ChatNotifyRule notifyRule;

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

    private ChatOutbox(ChatMessage message, Long recipientSetId, ChatNotifyRule notifyRule, LocalDateTime now) {
        this.deliveryId = UUID.randomUUID();
        this.deliveryType = ChatDeliveryType.MESSAGE;
        this.messageId = message.getId();
        this.chatRoomId = message.getChatRoom().getId();
        this.roomSequence = message.getRoomSequence();
        this.recipientSetId = recipientSetId;
        this.notifyRule = notifyRule;
        this.schemaVersion = SCHEMA_VERSION;
        this.status = ChatOutboxStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 저장한 메시지의 전달 작업.
     *
     * @param recipientSetId 이 메시지의 수신자로 확정한 불변 수신자 집합
     */
    public static ChatOutbox forMessage(ChatMessage message, Long recipientSetId, ChatNotifyRule notifyRule,
            LocalDateTime now) {
        if (message.getId() == null || recipientSetId == null || notifyRule == null) {
            throw new IllegalStateException("저장한 메시지·수신자 집합·알림 규칙이 필요합니다.");
        }
        return new ChatOutbox(message, recipientSetId, notifyRule, now);
    }
}
