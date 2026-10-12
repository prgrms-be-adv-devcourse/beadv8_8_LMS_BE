package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;

public record ChatRoomResponse(
        Long chatRoomId,
        ChatRoomType roomType,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        List<ChatRoomMemberResponse> members
) {

    public static ChatRoomResponse of(ChatRoom chatRoom, List<ChatRoomMemberResponse> members) {
        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getRoomType(), chatRoom.getStatus(),
                chatRoom.getCreatedAt(), members);
    }
}
