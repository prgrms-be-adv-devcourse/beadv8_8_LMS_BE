package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 끝난({@code COMPLETED}) HP 작업의 결과 (D57 (1), D73).
 * {@link #VOIDED}는 지갑 보류 무효화 결과 {@code HoldVoidOutcome.VOIDED}(보류 없이 무효 표시만 남김)와 뜻이 다르다.
 */
@Schema(description = """
        끝난 HP 작업의 결과
        - `SUCCEEDED`: 지갑이 처리했다
        - `REJECTED`: 보류가 확정 거절됐다 (잔액 부족, 또는 보류 무효화된 대상)
        - `SKIPPED`: 실행하기 전에 필요 없어진 보류
        - `VOIDED`: 결과를 확정하지 못한 보류를 관리자가 보류 무효화로 끝냈다. 지갑 처리는 무효화 작업(`HOLD_VOID`)이 한다""")
public enum LectureHpTaskOutcome {
    SUCCEEDED,
    REJECTED,
    SKIPPED,
    VOIDED
}
