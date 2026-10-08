package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지. {@code roomSequence}는 방 단위 순번으로, 참여자별 조회 범위를 정하는 기준이다.
 */
@Entity
@Table(name = "chat_message", schema = "chat",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_room_id", "room_sequence"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "room_sequence", nullable = false)
    private long roomSequence;

    @Column(nullable = false)
    private Long senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatMessageType messageType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    private LocalDateTime deletedAt;

    private ChatMessage(ChatRoom chatRoom, Long senderUserId, ChatMessageType messageType, String content,
            LocalDateTime now) {
        this.chatRoom = chatRoom;
        this.roomSequence = chatRoom.issueSequence();
        this.senderUserId = senderUserId;
        this.messageType = messageType;
        this.content = content;
        this.sentAt = now;
    }

    public static ChatMessage text(ChatRoom chatRoom, Long senderUserId, String content, LocalDateTime now) {
        if (content == null || content.isBlank()) {
            throw new ChatException(ChatErrorCode.EMPTY_MESSAGE);
        }
        return new ChatMessage(chatRoom, senderUserId, ChatMessageType.TEXT, content, now);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
