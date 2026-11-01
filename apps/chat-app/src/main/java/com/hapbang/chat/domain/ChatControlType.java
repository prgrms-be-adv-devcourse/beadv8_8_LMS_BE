package com.hapbang.chat.domain;

/**
 * 연결 제어 작업의 종류. 제어 outbox·제어 Stream·제어 채널이 같은 값을 쓴다.
 */
public enum ChatControlType {

    /** 회원의 최신 채팅 자격을 다시 확인하고, 채팅할 수 없으면 그 회원의 로컬 연결을 닫는다 */
    RECHECK_USER
}
