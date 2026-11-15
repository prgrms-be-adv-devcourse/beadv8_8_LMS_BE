package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 서비스 간 HP 대사 실행 상태 (#581) */
@Getter
@RequiredArgsConstructor
public enum HpReconciliationStatus {
    RUNNING("실행 중"),
    COMPLETED("끝까지 비교함. 이 실행만 지갑 보류 목록 커서를 앞으로 옮긴다"),
    INCOMPLETE("지갑 조회 실패(회로 열림 포함)·예외·앱 중단으로 끝까지 비교하지 못함. 커서를 옮기지 않고 다음 실행에서 다시 본다");

    private final String description;
}
