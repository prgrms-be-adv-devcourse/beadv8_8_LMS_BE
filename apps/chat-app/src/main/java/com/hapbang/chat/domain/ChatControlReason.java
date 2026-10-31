package com.hapbang.chat.domain;

/**
 * 연결 제어 작업을 남긴 이유.
 */
public enum ChatControlReason {

    /** Kafka 회원 변경 이벤트를 반영한 결과 채팅할 수 없음 */
    MEMBER_MODIFIED,

    /** 기존 회원 백필로 반영한 결과 채팅할 수 없음 */
    MEMBER_BACKFILL
}
