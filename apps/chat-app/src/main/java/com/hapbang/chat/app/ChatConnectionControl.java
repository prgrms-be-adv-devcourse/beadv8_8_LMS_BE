package com.hapbang.chat.app;

import java.time.Instant;

import com.hapbang.chat.domain.ChatControlReason;

/**
 * 회원 상태 변경·로그아웃에 따른 연결 제어 작업을 기록한다. 기록한 작업은 제어 Stream·제어 채널을 거쳐 로컬 연결 재검사로 이어진다.
 * 계약: {@code apps/chat-app/docs/contracts/control-recording.md}
 */
public interface ChatConnectionControl {

    /**
     * 회원의 로컬 연결 재검사 작업(RECHECK_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 같은 (회원, 원본 버전, 종류)는 한 번만 기록하고, 이미 있으면 아무것도 하지 않는다.
     *
     * @param memberVersion 반영한 회원 원본 버전. 버전 없는 회원 정보면 null
     */
    void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason);

    /**
     * 회원 로그아웃에 따른 로컬 연결 전체 종료 작업(LOGOUT_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 원본 버전이 없고 같은 회원이 여러 번 로그아웃하므로 호출마다 한 행을 남긴다. 같은 이벤트를 다시 처리해 행이 늘어도 연결을
     * 다시 닫을 뿐이라 해가 없다(설계 문서 Q8).
     *
     * @param loggedOutAt 이 시각보다 먼저 인증한 연결만 닫는다. 늦게 다시 온 옛 로그아웃이 그 뒤에 다시 로그인한 연결을 닫지 않게 한다
     */
    void requestLogout(Long userId, Instant loggedOutAt);
}
