package com.hapbang.billing.domain;

// 수익 HP 지급의 예정·중단·처리 중·완료·실패 상태를 구분한다.
// 지급(Settlement) 기능은 아직 구현 전이다.
public enum SettlementStatus {
    /** 지급 예정. 예정 시각(scheduled_at)이 되면 처리한다 */
    SCHEDULED,
    /** 지급 중단. 이의 제기 등으로 지급하지 않고 멈춰 둔다 */
    SUSPENDED,
    /** 지급 처리 중 */
    PROCESSING,
    /** 받는 쪽 수익 HP로 지급을 마쳤다 */
    COMPLETED,
    /** 지급 실패(예: 받는 쪽 지갑 없음). 재처리 대상 */
    FAILED
}
