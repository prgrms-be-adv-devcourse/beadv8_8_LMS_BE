package com.hapbang.chat.app;

import java.util.Collection;
import java.util.Set;

/**
 * 이 앱에 열려 있는 채팅 연결(로컬 WebSocket 세션) 목록. 실시간 전달·연결 재검사가 대상을 고를 때 쓴다.
 * replica 1개 기준이라 다른 앱의 연결은 모른다.
 */
public interface LocalChatConnections {

    /** 로컬 연결이 하나 이상 있는 회원 ID의 스냅샷 */
    Set<Long> connectedUserIds();

    /** 주어진 회원 중 로컬 연결이 있는 회원 */
    Set<Long> connectedAmong(Collection<Long> userIds);

    /** 그 회원의 로컬 연결 ID(STOMP 세션 ID)의 스냅샷. 없으면 빈 집합 */
    Set<String> sessionIdsOf(Long userId);

    /** 이 로컬 연결이 아직 열려 있는지 */
    boolean isConnected(String sessionId);

    /**
     * 그 회원의 로컬 연결을 모두 채팅 불가({@code 1008 CHAT_NOT_ALLOWED})로 닫는다. 이미 닫혔으면 아무것도 하지 않는다.
     *
     * @return 닫기를 요청한 연결 수
     */
    int closeNotAllowed(Long userId);
}
