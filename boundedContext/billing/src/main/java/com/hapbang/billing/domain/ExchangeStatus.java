package com.hapbang.billing.domain;

// 수익 HP 환전 요청의 진행 상태를 구분하며 외부 처리 결과가 불명확하면 UNKNOWN으로 표현한다.
// 상태 의미는 billing AGENTS.md 11장(환전)을 따른다. 신청·취소는 #285, 관리자 승인·거절은 #294, 송금 결과는 후속 Issue에서 구현한다.
public enum ExchangeStatus {
    /** 환전 신청. 신청한 수익 HP를 보류하고 관리자 승인을 기다린다. 회원이 취소할 수 있다 */
    REQUESTED,
    /** 관리자 승인(#294). 보류를 유지한 채 정산일 송금을 기다린다. 회원이 취소할 수 없다 */
    APPROVED,
    /** 관리자 거절(#294). 보류한 수익 HP를 전부 수익 HP로 반환했다 */
    REJECTED,
    /** 정산일 송금을 처리 중이다 */
    PROCESSING,
    /** 송금 성공. 보류한 수익 HP를 차감했다 */
    COMPLETED,
    /** 송금 최종 실패. 보류한 수익 HP를 수익 HP로 반환한다 */
    FAILED,
    /** 송금 결과가 불명확하다. 보류를 유지한다 */
    UNKNOWN,
    /** 관리자 승인 전(REQUESTED) 회원이 취소했다. 보류한 수익 HP를 전부 반환했다 */
    CANCELED
}
