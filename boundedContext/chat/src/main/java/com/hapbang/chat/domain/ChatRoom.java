package com.hapbang.chat.domain;

import java.time.LocalDateTime;

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
 * 1:1 채팅방.
 * <ul>
 *     <li>{@code directKey}는 두 회원 ID로 만든 키다. 유일 제약으로 같은 두 회원의 활성 방을 하나로 제한한다.</li>
 *     <li>{@code lastSequence}는 방 단위 메시지 순번이다. 방을 잠근 상태에서만 발급한다.</li>
 * </ul>
 */
@Entity
@Table(name = "chat_room", schema = "chat",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_room_direct_key", columnNames = "direct_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatRoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatRoomStatus status;

    @Column(name = "direct_key")
    private String directKey;

    @Column(nullable = false)
    private long lastSequence;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    private ChatRoom(ChatRoomType roomType, String directKey, LocalDateTime now) {
        this.roomType = roomType;
        this.status = ChatRoomStatus.ACTIVE;
        this.directKey = directKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ChatRoom createDirect(Long userId, Long otherUserId, LocalDateTime now) {
        return new ChatRoom(ChatRoomType.DIRECT, directKeyOf(userId, otherUserId), now);
    }

    /**
     * 두 회원 ID를 정렬해 만든 키. 누가 먼저 요청해도 같은 값이다.
     */
    public static String directKeyOf(Long userId, Long otherUserId) {
        return Math.min(userId, otherUserId) + ":" + Math.max(userId, otherUserId);
    }

    /**
     * 다음 메시지 순번을 발급한다.
     */
    public long issueSequence() {
        return ++lastSequence;
    }
}
