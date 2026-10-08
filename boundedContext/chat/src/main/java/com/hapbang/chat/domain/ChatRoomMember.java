package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 채팅방 참여자. 방·유저당 한 행이다. 입장 이력은 {@link ChatRoomLog}에 남는다.
 */
@Entity
@Table(name = "chat_room_member", schema = "chat",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_room_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime joinedAt;

    private LocalDateTime leftAt;

    private LocalDateTime deletedAt;

    private ChatRoomMember(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.joinedAt = now;
    }

    public static ChatRoomMember join(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        return new ChatRoomMember(chatRoom, userId, now);
    }

    public boolean isActive() {
        return leftAt == null && deletedAt == null;
    }
}
