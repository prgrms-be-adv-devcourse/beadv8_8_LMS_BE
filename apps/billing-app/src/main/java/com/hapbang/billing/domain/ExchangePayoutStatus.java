package com.hapbang.billing.domain;

// 환전 송금 시도 하나의 진행 상태를 구분하며 외부 응답만으로 결과를 확정할 수 없으면 UNKNOWN으로 표현한다.
// 상태 의미는 billing AGENTS.md 11장(환전)을 따른다. 송금 실행·결과 반영은 #304.
public enum ExchangePayoutStatus {
    /** 송금을 아직 요청하지 않았다. 지금은 쓰지 않는다(송금 전에 PROCESSING으로 저장한다, #304) */
    PENDING,
    /** 송금을 요청했거나 요청하기 직전이며 결과를 기다린다. 같은 지급 요청 ID로 결과를 확인한다 */
    PROCESSING,
    /** 송금 성공. 환전의 보류 HP를 확정 차감했다 */
    COMPLETED,
    /** 송금 확정 실패. 보류 HP는 반환하지 않고 유지한다(다시 보내면 새 시도) */
    FAILED,
    /** 송금 결과가 불명확하다(시간 초과·연결 실패·5xx 등). 성공·실패로 단정하지 않고 같은 지급 요청 ID로 다시 확인한다 */
    UNKNOWN,
    /** 자동으로 처리할 수 없다(Mock 은행 409·그 밖의 4xx 거절, 성공 응답이 송금 기록과 다름). 보류 유지, 운영자 확인 (#304) */
    MANUAL_REVIEW_REQUIRED
}
