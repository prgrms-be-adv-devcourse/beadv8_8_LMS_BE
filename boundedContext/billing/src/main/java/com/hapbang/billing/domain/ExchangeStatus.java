package com.hapbang.billing.domain;

// 수익 HP 환전 요청의 진행 상태를 구분하며 외부 처리 결과가 불명확하면 UNKNOWN으로 표현한다.
// 환전 기능은 아직 구현 전이며, 상태 의미는 billing AGENTS.md 11장(환전)을 따른다.
public enum ExchangeStatus {
    /** 환전 신청. 신청한 수익 HP를 보류하고 정산일(매월 10일)을 기다린다 */
    REQUESTED,
    /** 정산일 송금을 처리 중이다 */
    PROCESSING,
    /** 송금 성공. 보류한 수익 HP를 차감했다 */
    COMPLETED,
    /** 송금 최종 실패. 보류한 수익 HP를 수익 HP로 반환한다 */
    FAILED,
    /** 송금 결과가 불명확하다. 보류를 유지한다 */
    UNKNOWN
}
