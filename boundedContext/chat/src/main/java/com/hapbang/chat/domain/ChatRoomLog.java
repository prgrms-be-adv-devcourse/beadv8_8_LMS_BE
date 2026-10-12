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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅방 이벤트 기록. INSERT만 하며 수정·삭제하지 않는다.
 */
@Entity
@Table(name = "chat_room_log", schema = "chat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false, updatable = false)
    private ChatRoom chatRoom;

    @Column(nullable = false, updatable = false)
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChatRoomEventType eventType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    private ChatRoomLog(ChatRoom chatRoom, Long actorUserId, ChatRoomEventType eventType, LocalDateTime now) {
        this.chatRoom = chatRoom;
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.occurredAt = now;
    }

    public static ChatRoomLog record(ChatRoom chatRoom, Long actorUserId, ChatRoomEventType eventType,
            LocalDateTime now) {
        return new ChatRoomLog(chatRoom, actorUserId, eventType, now);
    }
}
