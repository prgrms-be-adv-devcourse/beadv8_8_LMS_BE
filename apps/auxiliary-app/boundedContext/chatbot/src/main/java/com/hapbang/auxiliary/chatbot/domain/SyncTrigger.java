package com.hapbang.auxiliary.chatbot.domain;

/** 싱크 회차를 시작한 것 (#716) */
public enum SyncTrigger {

    /** 정기 배치 (종류마다 매시, #717부터. 그 전에는 주 1회) */
    SCHEDULED,
    /**
     * 재시도 배치. #717에서 매시 실행으로 바꾸며 없앴다. 그 전에 남은 회차 기록을 읽으려고 값만 둔다(새로 만들지 않는다)
     */
    RETRY,
    /** 관리자 수동 실행 */
    ADMIN
}
