package com.hapbang.chat.domain;

/**
 * 일반 메시지·시스템 안내·알림 메시지를 구분한다.
 */
public enum ChatMessageType {
    /** 회원이 보낸 일반 메시지 */
    TEXT,
    /** 시스템이 남기는 안내 메시지 (예: 퇴장, 재입장) */
    SYSTEM,
    /** 다른 모듈의 이벤트로 남기는 알림 (예: 출연 요청 도착). 종류와 출연 건 ID를 함께 저장한다 */
    NOTIFICATION
}
