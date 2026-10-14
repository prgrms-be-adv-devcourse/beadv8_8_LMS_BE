package com.hapbang.billing.domain;

// 수익 HP 지급의 예정·중단·처리 중·완료·실패·취소 상태를 구분한다.
// 출연 지급은 처리하면서 바로 COMPLETED 건을 만들고, 강의 지급은 주문 항목마다 SCHEDULED 건을 먼저 만든 뒤 같은 행의 상태를 바꾼다(#142).
public enum SettlementStatus {
    /** 지급 예정. 예정 시각(scheduled_at)이 지나면 지급할 수 있다 */
    SCHEDULED,
    /** 지급 중단. 이의 제기로 지급을 멈춰 둔다. 예정 시각이 지나도 지급하지 않으며, 해소되면 처음 예정 시각 그대로 SCHEDULED로 돌아간다 */
    SUSPENDED,
    /** 지급 처리 중 */
    PROCESSING,
    /** 받는 쪽 수익 HP로 지급을 마쳤다. 취소·중단으로 되돌리지 않는다 */
    COMPLETED,
    /** 지급 실패(예: 받는 쪽 지갑 없음). 새 행을 만들지 않고 같은 행을 재처리한다 */
    FAILED,
    /** 구매 취소·환불 결정으로 지급하지 않기로 한 종료 상태. 다시 예정·중단으로 되돌리거나 같은 원천으로 새로 만들지 않는다 (#142) */
    CANCELLED
}
