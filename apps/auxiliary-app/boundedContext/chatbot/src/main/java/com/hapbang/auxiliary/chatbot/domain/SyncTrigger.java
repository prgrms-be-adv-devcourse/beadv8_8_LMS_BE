package com.hapbang.auxiliary.chatbot.domain;

/** 싱크 회차를 시작한 것 (#716) */
public enum SyncTrigger {

    /** 주간 배치 (월요일 10:00) */
    SCHEDULED,
    /** 재시도 배치 (월요일 12:00, 그 종류의 오늘 성공 회차가 없을 때만) */
    RETRY,
    /** 관리자 수동 실행 */
    ADMIN
}
