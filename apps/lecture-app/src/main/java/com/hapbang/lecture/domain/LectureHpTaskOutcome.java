package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** 끝난({@code COMPLETED}) HP 작업의 결과 (D57 (1)) */
@Schema(description = """
        끝난 HP 작업의 결과
        - `SUCCEEDED`: 지갑이 처리했다
        - `REJECTED`: 보류가 확정 거절됐다 (잔액 부족)
        - `SKIPPED`: 실행하기 전에 필요 없어진 보류""")
public enum LectureHpTaskOutcome {
    SUCCEEDED,
    REJECTED,
    SKIPPED
}
