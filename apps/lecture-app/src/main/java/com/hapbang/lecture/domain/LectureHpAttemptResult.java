package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** HP 작업 시도 한 번의 결과 (D57 (3)·(5)). 실행 중인 시도는 결과가 없다 */
@Schema(description = """
        HP 작업 시도 결과
        - `SUCCEEDED`: 지갑이 처리했다
        - `TERMINAL_REJECTION`: 다시 보내도 같은 거절 (HOLD 잔액 부족, PAYOUT 이미 지급)
        - `RETRYABLE`: 지갑이 처리하지 않았다. 다시 보낸다
        - `UNKNOWN_RESULT`: 지갑이 처리했는지 모른다. 같은 요청 ID로 다시 보낸다
        - `INVARIANT_VIOLATION`: 있어서는 안 되는 결과. 바로 운영 확인 대상이다
        - `LEASE_EXPIRED`: 결과를 기록하기 전에 선점이 만료돼 다른 실행이 가져갔다""")
public enum LectureHpAttemptResult {
    SUCCEEDED,
    TERMINAL_REJECTION,
    RETRYABLE,
    UNKNOWN_RESULT,
    INVARIANT_VIOLATION,
    LEASE_EXPIRED
}
