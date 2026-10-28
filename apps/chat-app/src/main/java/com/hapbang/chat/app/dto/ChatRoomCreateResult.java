package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param created           새로 만든 방이면 true, 기존 방을 재사용했으면 false
 * @param rejoinNotices     퇴장했던 회원을 재입장시켰으면 남은 참여자에게 보낼 재입장 안내. 없으면 빈 목록
 * @param remainingUserIds  재입장 안내를 받을, 재입장 전부터 참여 중이던 회원
 */
public record ChatRoomCreateResult(
        ChatRoomResponse room,
        boolean created,
        List<ChatMessageResponse> rejoinNotices,
        List<Long> remainingUserIds
) {

    public static ChatRoomCreateResult created(ChatRoomResponse room) {
        return new ChatRoomCreateResult(room, true, List.of(), List.of());
    }
}
