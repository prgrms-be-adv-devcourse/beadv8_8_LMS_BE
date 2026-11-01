package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주문 상태. 주문은 HP 보류보다 먼저 저장되고, 모든 항목의 보류가 성공해야 {@code PAID}가 된다 (D58).
 */
@Schema(description = """
        주문 상태
        - `HOLD_PENDING`: 결제 처리 중. HP 보류 결과를 기다린다
        - `HOLD_COMPENSATING`: 결제 취소 처리 중. 보류가 거절되어 이미 성공한 보류를 반환하는 중이다
        - `HOLD_FAILED`: 결제 실패. 묶여 남은 HP가 없다
        - `PAID`: 결제 완료
        - `CANCELED`: 모든 항목이 구매 취소 또는 반환되었다""")
public enum LectureOrderStatus {
    /** 결제 처리 중(항목별 HP 보류 진행·확인 중) */
    HOLD_PENDING,
    /** 결제 취소 처리 중(거절 뒤 성공한 보류를 반환하는 중) */
    HOLD_COMPENSATING,
    /** 결제 실패(남은 보류 없음 확인) */
    HOLD_FAILED,
    /** 모든 항목의 HP 보류 성공 */
    PAID,
    /** 모든 항목이 구매 취소·반환되었다 */
    CANCELED;

    /** 결제가 끝나지 않은 단계(결제 처리 중·결제 취소 처리 중)인지 */
    public boolean isHoldInProgress() {
        return this == HOLD_PENDING || this == HOLD_COMPENSATING;
    }
}
