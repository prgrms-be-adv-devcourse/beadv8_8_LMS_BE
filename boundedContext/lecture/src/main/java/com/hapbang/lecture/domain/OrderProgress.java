package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code PAID}인 주문의 진행 상황. 저장하지 않고 항목 상태에서 파생한다.
 */
@Schema(description = """
        주문 진행 상황. 주문 `status`가 `PAID`일 때만 값이 있다
        - `IN_PROGRESS`: 아직 끝나지 않은 항목(구매, 구매 확정)이 있다
        - `COMPLETED`: 모든 항목이 끝났다(지급·구매 취소·반환)""")
public enum OrderProgress {
    /** 아직 끝나지 않은 항목(구매, 구매 확정)이 있음 */
    IN_PROGRESS,
    /** 모든 항목이 지급·구매 취소·반환 중 하나이고 지급이 1개 이상 */
    COMPLETED
}
