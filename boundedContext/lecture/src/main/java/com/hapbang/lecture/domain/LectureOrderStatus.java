package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주문 상태. 주문은 HP 보류가 성공한 경우에만 생기므로 결제 대기·실패 상태가 없다.
 */
@Schema(description = """
        주문 상태
        - `PAID`: 결제 완료
        - `CANCELED`: 모든 항목이 구매 취소 또는 반환되었다""")
public enum LectureOrderStatus {
    /** 주문 생성(HP 보류 성공) */
    PAID,
    /** 모든 항목이 구매 취소·반환되었다 */
    CANCELED
}
