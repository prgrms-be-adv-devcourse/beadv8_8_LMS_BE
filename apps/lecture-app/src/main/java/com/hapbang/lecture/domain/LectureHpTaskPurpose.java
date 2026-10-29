package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업을 만든 업무. 결과를 반영하는 처리기도 목적마다 하나다 (D58·D59, D65) */
@Schema(description = """
        HP 작업 목적
        - `ORDER_HOLD`: 주문 항목 보류
        - `HOLD_COMPENSATION`: 일부 보류가 거절돼 이미 성공한 보류를 반환
        - `CANCEL`: 구매 취소 반환
        - `RETURN`: 이의 제기 인정 반환
        - `PAYOUT`: 판매자 지급""")
@Getter
@RequiredArgsConstructor
public enum LectureHpTaskPurpose {
    ORDER_HOLD(LectureHpTaskType.HOLD),
    HOLD_COMPENSATION(LectureHpTaskType.RELEASE),
    CANCEL(LectureHpTaskType.RELEASE),
    RETURN(LectureHpTaskType.RELEASE),
    PAYOUT(LectureHpTaskType.PAYOUT);

    private final LectureHpTaskType taskType;
}
