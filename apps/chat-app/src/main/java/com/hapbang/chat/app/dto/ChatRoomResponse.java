package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;

/**
 * @param lastMessage 조회한 회원이 볼 수 있는 가장 최근 메시지. 없으면 null
 * @param unreadCount 조회한 회원이 안 읽은 상대 메시지 수(시스템 안내 포함)
 */
public record ChatRoomResponse(
        Long chatRoomId,
        ChatRoomType roomType,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        List<ChatRoomMemberResponse> members,
        ChatMessageResponse lastMessage,
        long unreadCount
) {
    public static ChatRoomResponse of(ChatRoom chatRoom, List<ChatRoomMemberResponse> members,
            ChatMessageResponse lastMessage, long unreadCount) {
        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getRoomType(), chatRoom.getStatus(),
                chatRoom.getCreatedAt(), members, lastMessage, unreadCount);
    }
}
