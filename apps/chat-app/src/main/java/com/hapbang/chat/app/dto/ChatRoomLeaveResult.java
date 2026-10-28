package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param left             이번 요청으로 퇴장했으면 true. 이미 퇴장했거나 옛 참여 세대의 요청이면 false
 * @param leftNotice       남은 참여자에게 보낼 퇴장 안내 메시지. 퇴장하지 않았으면 null
 * @param remainingUserIds 퇴장 안내를 받을 남은 참여자
 * @param roomEnded        마지막 참여자가 나가 방이 종료됐으면 true
 */
public record ChatRoomLeaveResult(
        boolean left,
        ChatMessageResponse leftNotice,
        List<Long> remainingUserIds,
        boolean roomEnded
) {

    public static ChatRoomLeaveResult alreadyLeft() {
        return new ChatRoomLeaveResult(false, null, List.of(), false);
    }
}
