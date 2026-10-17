package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;

/**
 * @param clientMessageId  보낸 사람이 STOMP 전송에 붙인 전송 ID. 보낸 사람 본인에게만 담고(실시간 전달·이력 조회·방 목록),
 *                         다른 참여자에게는 null
 * @param notificationKind 알림 메시지({@code NOTIFICATION})의 종류. 다른 메시지는 null
 * @param referenceId       알림이 가리키는 출연 건 ID (요청서 ID 또는 지원 ID). 다른 메시지는 null
 * @param parentReferenceId 알림 대상의 상위 출연 건 ID (지원 알림의 모집글 ID). 없으면 null
 */
public record ChatMessageResponse(
        Long messageId,
        Long chatRoomId,
        long roomSequence,
        Long senderUserId,
        ChatMessageType messageType,
        String content,
        LocalDateTime sentAt,
        String clientMessageId,
        NotificationKind notificationKind,
        Long referenceId,
        Long parentReferenceId
) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getChatRoom().getId(), message.getRoomSequence(),
                message.getSenderUserId(), message.getMessageType(), message.getContent(), message.getSentAt(), null,
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId());
    }

    /** 조회한 회원 기준 응답. 본인이 보낸 메시지에만 전송 ID를 담는다 */
    public static ChatMessageResponse of(ChatMessage message, Long viewerUserId) {
        ChatMessageResponse response = from(message);
        return message.isSentBy(viewerUserId) ? response.withClientMessageId(message.getClientMessageId()) : response;
    }

    public ChatMessageResponse withClientMessageId(String clientMessageId) {
        return new ChatMessageResponse(messageId, chatRoomId, roomSequence, senderUserId, messageType, content, sentAt,
                clientMessageId, notificationKind, referenceId, parentReferenceId);
    }
}
