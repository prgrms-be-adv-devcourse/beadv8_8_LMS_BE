package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** 서비스 간 HP 대사 실행 상태 (#580, D74) */
@Schema(description = """
        HP 대사 실행 상태
        - `RUNNING`: 실행 중
        - `COMPLETED`: 끝까지 비교했다
        - `INCOMPLETE`: 지갑 조회 실패 등으로 중단했다. 커서를 옮기지 않아 다음 실행이 같은 범위를 다시 본다""")
public enum LectureHpReconciliationStatus {
    RUNNING,
    COMPLETED,
    INCOMPLETE
}
