package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.AppearanceApplicationSubmittedData;
import com.hapbang.chat.domain.AppearanceRequestSentData;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.NotificationKind;

/**
 * 메시지 응답 (REST 이력·방 목록의 마지막 메시지·STOMP 프레임 공용).
 * <p>
 * 알림 값은 알림 종류마다 필드 하나를 두고 {@code notificationKind}에 맞는 필드만 채운다(나머지는 null). 프론트가 종류별
 * 템플릿으로 문구를 만들고, 값이 있는 알림의 {@code content}는 빈 문자열이다. 문구로 남긴 기존 알림은 종류별 필드가 모두 null이고
 * {@code content}를 보여 준다. 계약: {@code apps/chat-app/docs/contracts/notification-data.md}
 *
 * @param clientMessageId                보낸 사람이 STOMP 전송에 붙인 전송 ID. 보낸 사람 본인에게만 담고(실시간 전달·이력 조회·방 목록),
 *                                       다른 참여자에게는 null
 * @param notificationKind               알림 메시지({@code NOTIFICATION})의 종류. 다른 메시지는 null
 * @param referenceId                    알림이 가리키는 출연 건 ID (요청서 ID 또는 지원 ID). 다른 메시지는 null
 * @param parentReferenceId              알림 대상의 상위 출연 건 ID (지원 알림의 모집글 ID). 없으면 null
 * @param appearanceRequestSent          출연 요청 도착 알림({@code APPEARANCE_REQUEST_SENT})의 값. 다른 메시지는 null
 * @param appearanceApplicationSubmitted 출연 모집 지원 도착 알림({@code APPEARANCE_APPLICATION_SUBMITTED})의 값. 다른 메시지는 null
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
        Long parentReferenceId,
        AppearanceRequestSentData appearanceRequestSent,
        AppearanceApplicationSubmittedData appearanceApplicationSubmitted
) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getChatRoom().getId(), message.getRoomSequence(),
                message.getSenderUserId(), message.getMessageType(), message.getContent(), message.getSentAt(), null,
                message.getNotificationKind(), message.getReferenceId(), message.getParentReferenceId(),
                message.readAppearanceRequestSent(), message.readAppearanceApplicationSubmitted());
    }

    /** 조회한 회원 기준 응답. 본인이 보낸 메시지에만 전송 ID를 담는다 */
    public static ChatMessageResponse of(ChatMessage message, Long viewerUserId) {
        ChatMessageResponse response = from(message);
        return message.isSentBy(viewerUserId) ? response.withClientMessageId(message.getClientMessageId()) : response;
    }

    public ChatMessageResponse withClientMessageId(String clientMessageId) {
        return new ChatMessageResponse(messageId, chatRoomId, roomSequence, senderUserId, messageType, content, sentAt,
                clientMessageId, notificationKind, referenceId, parentReferenceId, appearanceRequestSent,
                appearanceApplicationSubmitted);
    }
}
