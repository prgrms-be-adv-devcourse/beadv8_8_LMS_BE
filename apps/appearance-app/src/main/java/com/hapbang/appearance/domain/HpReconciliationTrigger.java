package com.hapbang.appearance.domain;

/** 서비스 간 HP 대사 실행 계기 (#581) */
public enum HpReconciliationTrigger {
    /** 매일 배치 */
    SCHEDULED,
    /** 관리자 수동 실행 */
    MANUAL
}
