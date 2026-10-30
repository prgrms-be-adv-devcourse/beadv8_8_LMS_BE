package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주문 항목 상태. 구매 확정·구매 취소·지급·이의 제기는 항목 단위로 일어난다.
 */
@Schema(description = """
        주문 항목 상태
        - `HOLD_PENDING`: 결제 처리 중. 주문의 결제 단계와 같다. 볼 수 없고 같은 강의를 다시 살 수 없다
        - `HOLD_COMPENSATING`: 결제 취소 처리 중. 주문의 결제 단계와 같다
        - `HOLD_FAILED`: 결제 실패. 같은 강의를 다시 살 수 있다
        - `PURCHASED`: 구매. `autoConfirmAt` 전까지 구매 취소할 수 있다. 영상·자료를 보기 전에 재생 동의가 필요하다
        - `CONFIRMED`: 구매 확정. 구매 취소할 수 없다
        - `CANCELED`: 구매 취소. HP가 반환되었고 더 볼 수 없다
        - `RETURNED`: 이의 제기 인정. HP가 반환되었고 더 볼 수 없다
        - `PAID_OUT`: 판매자에게 지급 완료. 계속 볼 수 있다""")
public enum OrderItemStatus {
    /** 결제 처리 중. 주문 전체의 결제 단계를 복제한다(D58) */
    HOLD_PENDING,
    /** 결제 취소 처리 중. 주문 전체의 결제 단계를 복제한다 */
    HOLD_COMPENSATING,
    /** 결제 실패. 남은 보류가 없다 */
    HOLD_FAILED,
    /** 구매(HP 보류). 구매 확정 전이다 */
    PURCHASED,
    /** 구매 확정. 구매일 + 30일이 지나면 지급한다 */
    CONFIRMED,
    /** 구매 취소(HP 반환). 확정 전에만 된다 */
    CANCELED,
    /** 이의 제기 인정(HP 반환) */
    RETURNED,
    /** 판매자에게 지급. 보유 중으로 보아 다시 살 수 없다(D5) */
    PAID_OUT;

    /** 결제 단계(결제 처리 중·결제 취소 처리 중·결제 실패)인지 */
    public boolean isHoldStage() {
        return this == HOLD_PENDING || this == HOLD_COMPENSATING || this == HOLD_FAILED;
    }

    /** 보유 중인지. 같은 강의를 다시 살 수 없다 (D5, D69 (4)). 부분 유일 인덱스 조건과 같다 */
    public boolean isHeld() {
        return this == HOLD_PENDING || this == HOLD_COMPENSATING || isViewable();
    }

    /** 시청 가능한지 (D69 (4)) */
    public boolean isViewable() {
        return this == PURCHASED || this == CONFIRMED || this == PAID_OUT;
    }
}
