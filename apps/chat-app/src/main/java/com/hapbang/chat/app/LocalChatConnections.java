package com.hapbang.chat.app;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.hapbang.chat.app.dto.ChatReauthResult;

/**
 * 이 앱에 열려 있는 채팅 연결(로컬 WebSocket 세션) 중 웹소켓 연결 티켓으로 회원을 결합한 연결 목록. 실시간 전달·연결 재검사·
 * 연결 제어가 대상을 고를 때 쓴다.
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

    /** 이 연결이 닫히는 시각(연결 만료). 회원을 결합하지 않았거나 닫힌 연결이면 null */
    Instant authenticatedUntil(String sessionId);

    /**
     * 열린 연결의 연결 만료를 새 티켓의 값으로 바꾼다(설계 문서 6장 "재인증"). 티켓의 회원이 이 연결에 결합한 회원이고, 연결이 아직
     * 만료 전이며 닫히는 중이 아닐 때만 바꾸고 만료 종료를 다시 예약한다. 인증 시각(로그아웃 표식과 비교하는 값)은 처음 연결한 티켓의
     * 발급 시각 그대로 둔다. 실패하면 아무것도 바꾸지 않는다(연결은 기존 만료에 닫힌다).
     *
     * @param userId              쓴 티켓의 회원
     * @param connectionExpiresAt 쓴 티켓의 연결 만료
     */
    ChatReauthResult reauthenticate(String sessionId, Long userId, Instant connectionExpiresAt);

    /**
     * 그 회원의 로컬 연결을 모두 채팅 불가({@code 1008 CHAT_NOT_ALLOWED})로 닫는다. 이미 닫혔으면 아무것도 하지 않는다.
     *
     * @return 닫기를 요청한 연결 수
     */
    int closeNotAllowed(Long userId);

    /**
     * 그 회원의 로컬 연결을 모두 로그아웃({@code 1008 LOGGED_OUT})으로 닫는다. 회원 자격과 관계없이 닫는다(설계 문서 Q8).
     * 이미 닫혔으면 아무것도 하지 않는다.
     *
     * @return 닫기를 요청한 연결 수
     */
    int closeLoggedOut(Long userId);

    /**
     * 그 회원의 로컬 연결 중 {@code loggedOutAt}보다 먼저 인증한(처음 연결한 티켓을 발급받은) 연결을 로그아웃({@code 1008 LOGGED_OUT})으로
     * 닫는다. 회원 자격과 관계없이 닫고, 로그아웃 뒤에 인증한 연결은 둔다(설계 문서 Q8). 이미 닫혔으면 아무것도 하지 않는다.
     *
     * @return 닫기를 요청한 연결 수
     */
    int closeLoggedOutBefore(Long userId, Instant loggedOutAt);

    /**
     * 연결 만료(access token {@code exp})가 지난 로컬 연결을 {@code 4401 TOKEN_EXPIRED}로 닫는다. 만료 예약의 두 번째 확인이다.
     * 닫기를 기다리지 않는다.
     *
     * @return 닫기를 맡긴 연결 수
     */
    int closeExpired();

    /**
     * 회원별 로그아웃 시각보다 먼저 인증한 로컬 연결을 {@code 1008 LOGGED_OUT}으로 닫는다(설계 문서 6장·Q8). 로그아웃 뒤에 인증한
     * 연결은 두고, 닫기를 기다리지 않는다.
     *
     * @return 닫기를 맡긴 연결 수
     */
    int closeLoggedOutBefore(Map<Long, Instant> loggedOutAt);
}
