package com.hapbang.chat.domain;

/**
 * 연결 제어 작업의 종류. 제어 outbox·제어 Stream·제어 채널이 같은 값을 쓴다.
 */
public enum ChatControlType {

    /** 회원의 최신 채팅 자격을 다시 확인하고, 채팅할 수 없으면 그 회원의 로컬 연결을 닫는다 */
    RECHECK_USER,

    /**
     * 회원이 로그아웃했다. 자격과 관계없이 그 회원의 로컬 연결을 모두 {@code 1008 LOGGED_OUT}으로 닫는다(설계 문서 Q8).
     * 원본 버전이 없고 같은 회원이 여러 번 로그아웃하므로 이벤트마다 한 행을 남긴다(중복 식별 없음). 다시 처리돼도 닫기만 반복한다
     */
    LOGOUT_USER
}
