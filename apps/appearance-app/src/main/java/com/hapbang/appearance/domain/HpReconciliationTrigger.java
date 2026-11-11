package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 서비스 간 HP 대사 실행 계기 (#581) */
@Getter
@RequiredArgsConstructor
public enum HpReconciliationTrigger {
    SCHEDULED("매일 배치"),
    MANUAL("관리자 수동 실행");

    private final String description;
}
