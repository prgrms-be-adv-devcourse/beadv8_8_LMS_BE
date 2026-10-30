package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;

/**
 * 관리자 기록 조회의 메시지.
 *
 * @param notificationData 알림 메시지의 종류별 값. {@link ChatMessageResponse#notificationData()}와 같다
 */
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
        Long parentReferenceId,
        Map<String, Object> notificationData
) {

    public static ChatMessageHistoryResponse from(ChatMessage message) {
        return new ChatMessageHistoryResponse(message.getId(), message.getRoomSequence(), message.getSenderUserId(),
                message.getMessageType(), message.getContent(), message.getSentAt(), message.getDeletedAt(),
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId(),
                message.getNotificationData());
    }
}
