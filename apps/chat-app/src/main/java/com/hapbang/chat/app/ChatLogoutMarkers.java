package com.hapbang.chat.app;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 회원 로그아웃 표식(설계 문서 6장·Q8). 로그아웃 시각을 회원마다 남기고, 하트비트가 그보다 먼저 인증한 로컬 연결을 닫는다.
 * 제어 채널(Pub/Sub)로 받은 로그아웃 종료를 놓쳐도 하트비트 주기 안에 회복하기 위한 두 번째 확인이다.
 * Redis가 없는 실행(실시간 전달을 끈 테스트)에는 빈이 없다. 실패는 예외로 알린다.
 */
public interface ChatLogoutMarkers {

    /** 그 회원이 {@code loggedOutAt}에 로그아웃했다고 남긴다. 이미 같거나 더 늦은 값이 있으면 두고, 더 늦은 값일 때만 덮는다(원자적) */
    void markLoggedOut(Long userId, Instant loggedOutAt);

    /** 주어진 회원의 로그아웃 시각을 한 번에 읽는다. 표식이 없는 회원은 결과에 없다 */
    Map<Long, Instant> loggedOutAt(Collection<Long> userIds);
}
