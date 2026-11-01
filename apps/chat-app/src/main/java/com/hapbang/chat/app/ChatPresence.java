package com.hapbang.chat.app;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 접속 중인 회원(Redis). 연결이 열리고 닫힐 때 기록하고 주기적으로 만료를 늘린다. 비정상 종료한 연결은 만료로 사라진다.
 * 새 메시지 알림 대상을 고를 때 방 참여자 중 접속 중인 회원을 한 번에 찾는다(설계 Q21).
 * Redis가 없는 실행(실시간 전달을 끈 테스트)에는 빈이 없다. 실패는 예외로 알린다.
 */
public interface ChatPresence {

    /** 연결이 열렸다 */
    void connected(Long userId, String sessionId);

    /** 연결이 닫혔다. 그 회원의 살아 있는 연결이 없으면 접속 중 목록에서도 뺀다 */
    void disconnected(Long userId, String sessionId);

    /** 이 앱의 연결 전부의 만료를 늘린다 */
    void heartbeat(Map<Long, Set<String>> sessionIdsByUserId);

    /** 만료가 지난 접속 정보를 지운다 */
    void purgeExpired();

    /** 주어진 회원 중 접속 중인 회원 */
    Set<Long> onlineAmong(Collection<Long> userIds);
}
