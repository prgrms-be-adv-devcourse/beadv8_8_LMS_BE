package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 출연 알림을 남긴 결과. 커밋 후 Controller·리스너가 STOMP로 보낸다.
 *
 * @param sent             알림 메시지와 받을 회원(전달: 두 참여자, 새 메시지 알림: 받는 사람)
 * @param rejoinNotices    받는 사람이 나갔다가 재입장했으면 남아 있던 회원에게 보낼 재입장 안내
 * @param remainingUserIds 재입장 안내를 받을 회원
 */
public record ChatNotificationResult(
        SentChatMessage sent,
        List<ChatMessageResponse> rejoinNotices,
        List<Long> remainingUserIds
) {
}
