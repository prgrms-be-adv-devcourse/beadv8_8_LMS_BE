package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** 서비스 간 HP 대사를 실행한 계기 (#580, D74) */
@Schema(description = """
        HP 대사 실행 계기
        - `SCHEDULED`: 매일 배치
        - `MANUAL`: 관리자 수동 실행""")
public enum LectureHpReconciliationTrigger {
    SCHEDULED,
    MANUAL
}
