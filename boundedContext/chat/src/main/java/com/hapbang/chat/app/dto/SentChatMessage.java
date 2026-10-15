package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param message          전송 ID 없는 메시지 응답. 보낸 사람에게는 전송 ID를 붙여 보낸다
 * @param deliverToUserIds 메시지를 받을 현재 참여자(보낸 사람 포함). 재전송이면 보낸 사람만
 * @param notifyUserIds    새 메시지 알림을 받을 참여자(보낸 사람 제외). 재전송이면 없음
 */
public record SentChatMessage(ChatMessageResponse message, List<Long> deliverToUserIds, List<Long> notifyUserIds) {
}
