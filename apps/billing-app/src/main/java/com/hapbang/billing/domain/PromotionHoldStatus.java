package com.hapbang.billing.domain;

// 프로모션 보류(주문 항목의 플랫폼 몫)의 상태를 구분한다.
public enum PromotionHoldStatus {
    /** 보류 중. 쿠폰 예산에서 프로모션 지갑 보류 칸으로 옮겨 묶어 두었다 */
    HELD,
    /** 지급됨. 판매자에게 수익 HP로 지급했다 */
    PAID,
    /** 복구됨. 반환해 같은 쿠폰 예산(예약 남은 예산)으로 되돌렸다 */
    RESTORED
}
