package com.hapbang.shared.billing.domain;

/**
 * 지갑 지급 건 상태 (#577, 보류·지급 조회 내부 API 응답). 지갑의 {@code SettlementStatus}와 같은 상수다(지갑 테스트가 일치를 확인한다).
 * 내부 HP 지급은 즉시 끝나 {@code COMPLETED}로 만들어진다. 나머지는 #142·#182 시절 강의 지급 예정 건에만 있을 수 있다.
 */
public enum BillingSettlementStatus {
    /** 지급 예정 */
    SCHEDULED,
    /** 지급 중단(이의 제기 등) */
    SUSPENDED,
    /** 지급 처리 중 */
    PROCESSING,
    /** 지급 완료 */
    COMPLETED,
    /** 지급 실패 */
    FAILED,
    /** 지급 예정 취소 */
    CANCELLED
}
