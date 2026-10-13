package com.hapbang.billing.domain;

// 환전 송금의 진행 상태를 구분하며 외부 응답만으로 결과를 확정할 수 없으면 UNKNOWN으로 표현한다.
// 환전 기능은 아직 구현 전이며, 상태 의미는 billing AGENTS.md 11장(환전)을 따른다.
public enum ExchangePayoutStatus {
    /** 송금을 아직 요청하지 않았다 */
    PENDING,
    /** 송금을 요청했고 결과를 기다린다 */
    PROCESSING,
    /** 송금 성공 */
    COMPLETED,
    /** 송금 최종 실패 */
    FAILED,
    /** 송금 결과가 불명확하다. 성공·실패로 단정하지 않는다 */
    UNKNOWN
}
