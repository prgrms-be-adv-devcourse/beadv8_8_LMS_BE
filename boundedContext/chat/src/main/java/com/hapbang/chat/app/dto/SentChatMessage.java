package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param deliverToUserIds 메시지를 받을 현재 참여자(보낸 사람 포함)
 * @param notifyUserIds    새 메시지 알림을 받을 참여자(보낸 사람 제외)
 */
public record SentChatMessage(ChatMessageResponse message, List<Long> deliverToUserIds, List<Long> notifyUserIds) {
}
