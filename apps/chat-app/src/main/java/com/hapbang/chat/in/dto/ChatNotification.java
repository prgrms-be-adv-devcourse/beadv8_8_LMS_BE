package com.hapbang.chat.in.dto;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomActivity;

/**
 * 새 메시지 알림. 방을 열지 않은 참여자의 연결에 /user/queue/chats/notifications로 방별로 묶어 보낸다(resync-frame.md).
 * 세대는 payload의 {@code participationVersion}으로 비교한다(헤더 없음).
 *
 * @param lastSequence 묶음의 마지막 메시지 순번
 * @param messageCount 묶음 안에서 받는 회원에게 보이고 그 회원이 보내지 않은 메시지 수
 */
public record ChatNotification(
        NotificationType type,
        Long chatRoomId,
        int participationVersion,
        long lastSequence,
        int messageCount,
        ChatMessageResponse lastMessage
) {

    public enum NotificationType {
        ROOM_ACTIVITY
    }

    public static ChatNotification roomActivity(ChatRoomActivity activity) {
        return new ChatNotification(NotificationType.ROOM_ACTIVITY, activity.chatRoomId(),
                activity.participationVersion(), activity.lastSequence(), activity.messageCount(),
                activity.lastMessage());
    }
}
