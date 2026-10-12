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
 * 채팅방 참여자. 방·유저당 한 행이며, 다시 들어오면 같은 행을 갱신한다. 입장·퇴장 이력은 {@link ChatRoomLog}에 남는다.
 * <ul>
 *     <li>{@code visibleFromSequence}: 이 참여자가 볼 수 있는 첫 메시지 순번. 재입장하면 재입장 이후로 옮긴다.</li>
 *     <li>{@code participationVersion}: 참여 세대. 재입장할 때마다 1 늘어나며, 지연 도착한 옛 퇴장 요청을 구별한다.</li>
 * </ul>
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

    @Column(nullable = false)
    private long visibleFromSequence;

    @Column(nullable = false)
    private int participationVersion;

    private LocalDateTime deletedAt;

    private ChatRoomMember(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.joinedAt = now;
        this.visibleFromSequence = chatRoom.nextVisibleSequence();
        this.participationVersion = 1;
    }

    public static ChatRoomMember join(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        return new ChatRoomMember(chatRoom, userId, now);
    }

    public boolean isActive() {
        return leftAt == null && deletedAt == null;
    }

    public void leave(LocalDateTime now) {
        if (!isActive()) {
            throw new ChatException(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
        }
        this.leftAt = now;
    }

    /**
     * 재입장한다. 재입장 이전 메시지는 볼 수 없도록 조회 시작점을 옮긴다.
     */
    public void rejoin(LocalDateTime now) {
        if (isActive()) {
            return;
        }
        this.joinedAt = now;
        this.leftAt = null;
        this.visibleFromSequence = chatRoom.nextVisibleSequence();
        this.participationVersion++;
    }

    public boolean canView(long roomSequence) {
        return roomSequence >= visibleFromSequence;
    }

    /**
     * 요청이 지금 참여 세대에 대한 것인지 확인한다. 버전을 보내지 않은 요청은 현재 세대로 본다.
     */
    public boolean isCurrentParticipation(Integer participationVersion) {
        return participationVersion == null || participationVersion == this.participationVersion;
    }
}
