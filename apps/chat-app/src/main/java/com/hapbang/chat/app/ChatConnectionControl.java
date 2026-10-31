package com.hapbang.chat.app;

import com.hapbang.chat.domain.ChatControlReason;

/**
 * 회원 상태 변경에 따른 연결 제어 작업을 기록한다. 기록한 작업은 제어 Stream·제어 채널을 거쳐 로컬 연결 재검사로 이어진다.
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
}
