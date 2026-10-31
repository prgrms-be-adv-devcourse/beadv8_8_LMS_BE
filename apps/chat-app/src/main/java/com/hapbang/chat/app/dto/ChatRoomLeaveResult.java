package com.hapbang.chat.app.dto;

/**
 * @param left       이번 요청으로 퇴장했으면 true. 이미 퇴장했거나 옛 참여 세대의 요청이면 false
 * @param leftNotice 남은 참여자에게 보일 퇴장 안내 메시지. 퇴장하지 않았으면 null. 전달은 outbox가 맡는다
 * @param roomEnded  마지막 참여자가 나가 방이 종료됐으면 true
 * @param leftParticipationVersion 이번 요청으로 끝난 참여 세대. 퇴장하지 않았으면 0. 퇴장 뒤 구독 회수는 이 세대 이하의 구독만 한다
 *                                 (늦게 실행돼도 재입장한 새 세대의 구독을 지우지 않게)
 */
public record ChatRoomLeaveResult(
        boolean left,
        ChatMessageResponse leftNotice,
        boolean roomEnded,
        int leftParticipationVersion
) {

    public static ChatRoomLeaveResult alreadyLeft() {
        return new ChatRoomLeaveResult(false, null, false, 0);
    }
}
