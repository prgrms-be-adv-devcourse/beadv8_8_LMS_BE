package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 출연 알림을 남긴 결과. 알림 메시지·재입장 안내의 전달은 같은 트랜잭션에 남긴 outbox가 맡는다.
 *
 * @param message       알림 메시지
 * @param rejoinNotices 받는 사람이 나갔다가 재입장했으면 남아 있던 회원에게 보일 재입장 안내
 */
public record ChatNotificationResult(
        ChatMessageResponse message,
        List<ChatMessageResponse> rejoinNotices
) {
}
