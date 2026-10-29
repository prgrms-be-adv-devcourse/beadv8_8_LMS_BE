package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** HP 작업 상태 (D57 (1)) */
@Schema(description = """
        HP 작업 상태
        - `PENDING`: 다음 시도 시각을 기다린다
        - `PROCESSING`: 실행기가 선점해 지갑에 요청 중이다
        - `COMPLETED`: 끝났다. 결과는 `outcome`에 있다
        - `DEAD`: 자동 처리가 멈췄다. 관리자가 원인을 확인하고 재시도한다""")
public enum LectureHpTaskStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    DEAD
}
