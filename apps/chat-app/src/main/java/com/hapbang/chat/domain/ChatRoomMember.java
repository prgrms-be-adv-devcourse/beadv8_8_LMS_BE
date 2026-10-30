package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;
import org.springframework.util.Assert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 *     <li>{@code lastReadSequence}: 마지막으로 읽은 메시지 순번. 입장·재입장하면 그때까지의 메시지는 읽은 것으로 본다.
 *     읽음 처리는 동시에 와도 줄어들지 않게 Repository의 조건부 갱신으로 올린다.</li>
 *     <li>{@code lastReceivedSequence}: 현재 참여 세대에서 어느 한 클라이언트가 빠짐없이 반영했다고 보고한 누적 수신 순번.
 *     확인 전에는 null(미확인)이다. 입장·재입장하면 null로 시작하고, 클라이언트 수신 확인으로만 Repository의 조건부 갱신으로 올린다.
 *     읽음 위치와 따로 관리한다.</li>
 * </ul>
 * <p>
 * 엔티티 저장은 바뀐 컬럼만 UPDATE한다({@link DynamicUpdate}). 읽음·수신 확인 순번은 방을 잠그지 않는 조건부 갱신으로 올라가므로,
 * 전체 컬럼을 쓰면 퇴장처럼 행을 읽은 뒤 저장하는 변경이 그 사이 커밋된 순번을 읽어 둔 옛 값으로 되돌린다.
 * 재입장은 비활성 행에서만 일어나고 수신 확인 갱신은 활성 행만 바꾸므로, 재입장이 새로 정한 값과 겹쳐 쓰지 않는다.
 */
@Entity
@DynamicUpdate
@Table(name = "chat_room_member", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_room_member_room_user",
                columnNames = {"chat_room_id", "user_id"}),
        indexes = @Index(name = "idx_chat_room_member_user_id_left_at", columnList = "user_id, left_at"))
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

    @Column(nullable = false)
    private long lastReadSequence;

    private Long lastReceivedSequence;

    private LocalDateTime deletedAt;

    private ChatRoomMember(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.joinedAt = now;
        this.visibleFromSequence = chatRoom.nextVisibleSequence();
        this.participationVersion = 1;
        this.lastReadSequence = chatRoom.getLastSequence();
    }

    public static ChatRoomMember join(ChatRoom chatRoom, Long userId, LocalDateTime now) {
        return new ChatRoomMember(chatRoom, userId, now);
    }

    public boolean isActive() {
        return leftAt == null && deletedAt == null;
    }

    public void leave(LocalDateTime now) {
        Assert.state(isActive(), "이미 나간 참여자는 다시 나갈 수 없습니다.");
        this.leftAt = now;
    }

    /**
     * 재입장한다. 재입장 이전 메시지는 볼 수 없도록 조회 시작점을 옮긴다.
     * 수신 확인 순번은 새 참여 세대에서 다시 확인하도록 참여 세대 변경과 함께 비운다(미확인).
     */
    public void rejoin(LocalDateTime now) {
        if (isActive()) {
            return;
        }
        this.joinedAt = now;
        this.leftAt = null;
        this.visibleFromSequence = chatRoom.nextVisibleSequence();
        this.participationVersion++;
        this.lastReadSequence = chatRoom.getLastSequence();
        this.lastReceivedSequence = null;
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
