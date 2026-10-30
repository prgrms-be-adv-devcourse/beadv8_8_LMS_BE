package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 처리 중 주문·항목에 보여 줄 처리 상태 (module.md "처리 상태", D65). 내부 작업 상태(PENDING·PROCESSING·DEAD)를
 * 그대로 보여 주지 않고 업무 의미로 바꾼다. 주문 응답에 싣는 것은 처리 중 상태가 생기는 #276·#277이다.
 */
@Schema(description = """
        처리 상태 (처리 중 항목에만)
        - `PROCESSING`: 정상 처리 중
        - `DELAYED`: 실패 후 재시도 중
        - `ACTION_REQUIRED`: 자동 처리가 멈췄다. 고객센터 확인이 필요하다""")
// 선언 순서가 심각도 순이다. 여러 작업의 처리 상태는 가장 나쁜(뒤의) 값으로 모은다 (D69 (2))
public enum ProcessingStatus {
    PROCESSING,
    DELAYED,
    ACTION_REQUIRED;

    /** 작업의 처리 상태. 끝난 작업은 처리 중이 아니라 {@code null}이다 */
    public static ProcessingStatus of(LectureHpTask task) {
        return switch (task.getStatus()) {
            case PENDING, PROCESSING -> task.getLastFailedAt() == null ? PROCESSING : DELAYED;
            case DEAD -> ACTION_REQUIRED;
            case COMPLETED -> null;
        };
    }
}
