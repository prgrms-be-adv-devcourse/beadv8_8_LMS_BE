package com.hapbang.appearance.domain;

/** 서비스 간 HP 대사 실행 상태 (#581) */
public enum HpReconciliationStatus {
    /** 실행 중 */
    RUNNING,
    /** 끝까지 비교함. 이 실행만 지갑 보류 목록 커서를 앞으로 옮긴다 */
    COMPLETED,
    /** 지갑 조회 실패(회로 열림 포함)나 예외로 중단함. 커서를 옮기지 않고 다음 실행에서 다시 본다 */
    INCOMPLETE
}
