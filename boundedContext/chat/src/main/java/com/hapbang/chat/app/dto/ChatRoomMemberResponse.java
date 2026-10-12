package com.hapbang.chat.app.dto;

import com.hapbang.chat.domain.ChatRoomMember;

/**
 * @param active               참여 중이면 true, 퇴장했으면 false
 * @param participationVersion 참여 세대. 퇴장 요청에 함께 보내면 지연 도착한 옛 요청을 무시할 수 있다.
 */
public record ChatRoomMemberResponse(Long userId, String nickname, boolean active, int participationVersion) {

    public static ChatRoomMemberResponse of(ChatRoomMember member, String nickname) {
        return new ChatRoomMemberResponse(member.getUserId(), nickname, member.isActive(),
                member.getParticipationVersion());
    }
}
