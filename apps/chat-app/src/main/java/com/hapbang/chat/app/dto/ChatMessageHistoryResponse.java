package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;

/**
 * 관리자 기록 조회의 메시지.
 *
 * @param appearanceRequestSent          출연 요청 도착 알림의 값. {@link ChatMessageResponse}와 같다
 * @param appearanceApplicationSubmitted 출연 모집 지원 도착 알림의 값. {@link ChatMessageResponse}와 같다
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
        AppearanceRequestSentData appearanceRequestSent,
        AppearanceApplicationSubmittedData appearanceApplicationSubmitted
) {

    public static ChatMessageHistoryResponse from(ChatMessage message) {
        return new ChatMessageHistoryResponse(message.getId(), message.getRoomSequence(), message.getSenderUserId(),
                message.getMessageType(), message.getContent(), message.getSentAt(), message.getDeletedAt(),
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId(),
                message.readAppearanceRequestSent(), message.readAppearanceApplicationSubmitted());
    }
}
