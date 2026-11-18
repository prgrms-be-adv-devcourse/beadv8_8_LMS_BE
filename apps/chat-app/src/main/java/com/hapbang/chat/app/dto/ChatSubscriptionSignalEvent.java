package com.hapbang.chat.app.dto;

/**
 * 방 topic 구독 하나에 거절·회수 프레임을 써야 한다는 앱 안 이벤트. 구독 허가·회수({@code DefaultChatRoomTopicService})가 내고, 진입
 * 어댑터({@code in/websocket})가 받은 스레드에서 바로 그 구독에 프레임을 쓴다. Kafka로 내보내거나 발행 기록에 남기지 않는다.
 *
 * @param sessionId                   프레임을 받을 연결
 * @param subscriptionId              프레임을 받을 구독
 * @param participationVersion        그 구독의 참여 세대
 * @param currentParticipationVersion 세대 불일치로 거절할 때만 그 회원의 현재 세대. 아니면 null
 */
public record ChatSubscriptionSignalEvent(
        Long roomId,
        String sessionId,
        String subscriptionId,
        int participationVersion,
        ChatSubscriptionSignal signal,
        ChatSubscriptionReason reason,
        Integer currentParticipationVersion
) {
}
