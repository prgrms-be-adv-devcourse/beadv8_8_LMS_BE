package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import org.springframework.util.Assert;

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
@Table(name = "chat_message", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_message_room_sequence",
                columnNames = {"chat_room_id", "room_sequence"}))
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

    /** 알림 메시지의 종류. 일반·시스템 메시지는 null */
    @Enumerated(EnumType.STRING)
    private NotificationKind notificationKind;

    /** 알림이 가리키는 출연 건 ID (다른 모듈의 ID). 일반·시스템 메시지는 null */
    private Long referenceId;

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
        Assert.state(hasContent(content), "메시지 내용이 비어 있습니다.");
        return new ChatMessage(chatRoom, senderUserId, ChatMessageType.TEXT, content, now);
    }

    /** 보낼 수 있는 메시지 내용인지. UseCase가 도메인 호출 전에 같은 기준으로 검사한다 */
    public static boolean hasContent(String content) {
        return content != null && !content.isBlank();
    }

    /**
     * 회원이 방을 나갔음을 알리는 시스템 메시지. 서버만 만든다. 보낸 사람은 나간 회원으로 기록한다.
     */
    public static ChatMessage leftNotice(ChatRoom chatRoom, Long leftUserId, String nickname, LocalDateTime now) {
        return new ChatMessage(chatRoom, leftUserId, ChatMessageType.SYSTEM, nickname + "님이 나갔습니다.", now);
    }

    /**
     * 퇴장했던 회원이 다시 들어왔음을 알리는 시스템 메시지. 서버만 만든다. 보낸 사람은 재입장한 회원으로 기록한다.
     */
    public static ChatMessage rejoinNotice(ChatRoom chatRoom, Long rejoinedUserId, String nickname,
            LocalDateTime now) {
        return new ChatMessage(chatRoom, rejoinedUserId, ChatMessageType.SYSTEM, nickname + "님이 다시 들어왔습니다.", now);
    }

    /**
     * 다른 모듈의 이벤트로 남기는 알림 메시지. 서버만 만든다. 보낸 사람은 행위자(예: 출연을 요청한 호스트)로 기록한다.
     */
    public static ChatMessage notification(ChatRoom chatRoom, Long senderUserId, NotificationKind kind,
            Long referenceId, String content, LocalDateTime now) {
        Assert.state(kind != null && referenceId != null, "알림 종류와 대상 ID가 필요합니다.");
        Assert.state(hasContent(content), "알림 내용이 비어 있습니다.");
        ChatMessage message = new ChatMessage(chatRoom, senderUserId, ChatMessageType.NOTIFICATION, content, now);
        message.notificationKind = kind;
        message.referenceId = referenceId;
        return message;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
