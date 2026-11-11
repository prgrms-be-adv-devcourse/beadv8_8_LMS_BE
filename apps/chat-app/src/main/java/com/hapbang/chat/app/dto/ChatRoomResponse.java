package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;

/**
 * @param members     1:1 방의 참여자(퇴장한 상대 포함). 단체방은 빈 목록이다(참여자 페이지 {@code GET /{chatRoomId}/members})
 * @param lastMessage 조회한 회원이 볼 수 있는 가장 최근 메시지. 없으면 null
 * @param unreadCount 조회한 회원이 안 읽은 상대 메시지 수(시스템 안내 포함)
 * @param group       단체방 정보. 1:1 방은 null
 */
public record ChatRoomResponse(
        Long chatRoomId,
        ChatRoomType roomType,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        List<ChatRoomMemberResponse> members,
        ChatMessageResponse lastMessage,
        long unreadCount,
        ChatGroupRoomSummary group
) {
    public static ChatRoomResponse of(ChatRoom chatRoom, List<ChatRoomMemberResponse> members,
            ChatMessageResponse lastMessage, long unreadCount) {
        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getRoomType(), chatRoom.getStatus(),
                chatRoom.getCreatedAt(), members, lastMessage, unreadCount, null);
    }

    /** 단체방 응답. 참여자 목록은 싣지 않는다 */
    public static ChatRoomResponse ofGroup(ChatRoom chatRoom, ChatGroupRoomSummary group,
            ChatMessageResponse lastMessage, long unreadCount, LocalDateTime now) {
        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getRoomType(), chatRoom.statusAt(now),
                chatRoom.getCreatedAt(), List.of(), lastMessage, unreadCount, group);
    }
}
