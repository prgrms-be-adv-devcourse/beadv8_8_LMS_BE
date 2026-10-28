package com.hapbang.chat.in.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.app.dto.ChatMessageResponse;

/**
 * 새 메시지 알림. /user/queue/chats/notifications로 받는 사람에게만 보낸다.
 */
public record ChatNotification(
        NotificationType type,
        Long chatRoomId,
        Long messageId,
        Long senderUserId,
        LocalDateTime sentAt
) {

    public enum NotificationType {
        NEW_MESSAGE
    }

    public static ChatNotification newMessage(ChatMessageResponse message) {
        return new ChatNotification(NotificationType.NEW_MESSAGE, message.chatRoomId(), message.messageId(),
                message.senderUserId(), message.sentAt());
    }
}
