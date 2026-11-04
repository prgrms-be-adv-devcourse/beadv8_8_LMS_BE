package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강의 검수 상태. 삭제는 상태가 아니라 {@code deletedAt}으로 표시한다.
 */
@Schema(description = """
        강의 검수 상태
        - `PENDING_INSPECTION`: 검수 대기. 판매자·관리자만 볼 수 있다
        - `REJECTED`: 반려. 판매자가 영상·자료를 고친 뒤 재검수를 신청할 수 있다
        - `ON_SALE`: 판매 중. 누구나 보고 살 수 있다""")
public enum LectureStatus {
    /** 등록·재검수 신청 직후. 관리자가 승인하거나 반려한다 */
    PENDING_INSPECTION,
    /** 관리자가 사유와 함께 반려했다. 영상·자료는 이 상태에서만 교체할 수 있다(D11) */
    REJECTED,
    /** 관리자가 승인했다. 장바구니 담기·주문은 이 상태에서만 받는다 */
    ON_SALE
}
