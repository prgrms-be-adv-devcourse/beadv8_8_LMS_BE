package com.hapbang.chat.app.dto;

import com.hapbang.chat.domain.ChatRoomMember;

public record ChatRoomMemberResponse(Long userId, String nickname) {

    public static ChatRoomMemberResponse of(ChatRoomMember member, String nickname) {
        return new ChatRoomMemberResponse(member.getUserId(), nickname);
    }
}
