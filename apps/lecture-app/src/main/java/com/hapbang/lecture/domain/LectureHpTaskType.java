package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** HP 작업 유형: 지갑에 보내는 요청 종류 (D57) */
@Schema(description = """
        HP 작업 유형
        - `HOLD`: 주문 항목 가격만큼 구매자 HP 보류
        - `RELEASE`: 보류 HP를 구매자에게 반환 (구매 취소·이의 제기 인정·보류 보상)
        - `PAYOUT`: 보류 HP를 판매자에게 지급""")
public enum LectureHpTaskType {
    HOLD,
    RELEASE,
    PAYOUT
}
