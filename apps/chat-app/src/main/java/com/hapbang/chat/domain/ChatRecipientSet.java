package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 불변 수신자 집합의 한 버전. 참여 변경(생성·퇴장·재입장) 때 방 잠금 아래 새 버전을 만들고, 만든 뒤에는 고치지 않는다.
 * 메시지 전달 작업은 저장 시점의 버전을 참조하므로, 나중에 들어온 회원이 이전 메시지의 수신자가 되지 않는다.
 * 항목은 {@link ChatRecipientSetMember}다.
 */
@Entity
@Table(name = "chat_recipient_set", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_recipient_set_room_version",
                columnNames = {"chat_room_id", "set_version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRecipientSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    /** 방 안에서 1부터 늘어나는 버전 */
    @Column(nullable = false)
    private int setVersion;

    /** 항목 수. 이 버전을 만들 때의 활성 참여자 수와 같다 */
    @Column(nullable = false)
    private int memberCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ChatRecipientSet(Long chatRoomId, int setVersion, int memberCount, LocalDateTime now) {
        this.chatRoomId = chatRoomId;
        this.setVersion = setVersion;
        this.memberCount = memberCount;
        this.createdAt = now;
    }

    public static ChatRecipientSet create(Long chatRoomId, int setVersion, int memberCount, LocalDateTime now) {
        return new ChatRecipientSet(chatRoomId, setVersion, memberCount, now);
    }
}
