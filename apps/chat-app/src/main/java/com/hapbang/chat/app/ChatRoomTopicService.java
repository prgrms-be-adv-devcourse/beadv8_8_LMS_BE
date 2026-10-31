package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ChatSubscriptionReason;

/**
 * 방 topic 구독의 허가·해제·회수(설계 4.2).
 */
public interface ChatRoomTopicService {

    /**
     * 방 topic 구독을 허가하고 목록에 넣는다. 거절하면 그 구독에 거절 프레임을 쓴다(연결은 닫지 않는다).
     *
     * @param participationVersion SUBSCRIBE의 {@code participation-version} 헤더 값
     * @return 넣었으면 true
     */
    boolean subscribe(Long roomId, Long userId, String sessionId, String subscriptionId, int participationVersion);

    /** 구독을 뺀다. 모르는 구독 ID는 무시한다 */
    void unsubscribe(String sessionId, String subscriptionId);

    /** 연결이 닫혔다. 그 연결의 방 구독을 모두 뺀다 */
    void sessionClosed(String sessionId);

    /**
     * 퇴장 등이 커밋된 뒤 그 회원의 그 방 로컬 구독 중 끝난 세대 이하의 구독만 회수하고 회수 프레임을 쓴다.
     *
     * @param endedParticipationVersion 끝난 참여 세대
     */
    void revokeMember(Long roomId, Long userId, int endedParticipationVersion, ChatSubscriptionReason reason);
}
