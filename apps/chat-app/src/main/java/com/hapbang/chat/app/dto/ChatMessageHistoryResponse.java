package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;

public record ChatMessageHistoryResponse(
        Long messageId,
        long roomSequence,
        Long senderUserId,
        ChatMessageType messageType,
        String content,
        LocalDateTime sentAt,
        LocalDateTime deletedAt,
        NotificationKind notificationKind,
        Long referenceId,
        Long parentReferenceId
) {

    public static ChatMessageHistoryResponse from(ChatMessage message) {
        return new ChatMessageHistoryResponse(message.getId(), message.getRoomSequence(), message.getSenderUserId(),
                message.getMessageType(), message.getContent(), message.getSentAt(), message.getDeletedAt(),
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId());
    }
}
