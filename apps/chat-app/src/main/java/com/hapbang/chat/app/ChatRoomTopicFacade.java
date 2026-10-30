package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * STOMP 진입 어댑터가 쓰는 방 topic 구독 진입점(설계 4.2). 로그인 사용자는 STOMP 연결 검사가 이미 확인했으므로
 * {@code @PreAuthorize}를 붙이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomTopicFacade {

    private final ChatRoomTopicService chatRoomTopicService;

    /** @return 넣었으면 true. 거절하면 그 구독에 거절 프레임을 쓴다 */
    public boolean subscribe(Long roomId, Long userId, String sessionId, String subscriptionId,
            int participationVersion) {
        return chatRoomTopicService.subscribe(roomId, userId, sessionId, subscriptionId, participationVersion);
    }

    /** 구독을 뺀다. 모르는 구독 ID는 무시한다 */
    public void unsubscribe(String sessionId, String subscriptionId) {
        chatRoomTopicService.unsubscribe(sessionId, subscriptionId);
    }

    /** 연결이 닫혔다. 그 연결의 방 구독을 모두 뺀다 */
    public void sessionClosed(String sessionId) {
        chatRoomTopicService.sessionClosed(sessionId);
    }
}
