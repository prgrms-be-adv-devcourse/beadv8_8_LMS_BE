package com.hapbang.billing.domain;

// 보류할 때 쓸 수 있는 HP 종류를 구분한다 (#241). 보류할 때 정한 HP 종류별 구성은 바뀌지 않고,
// 이후 반환·지급·확정 차감은 그 구성(남은 충전·수익 HP 보류와 충전 원금 배분)을 기준으로 처리한다.
public enum PointHoldType {
    /** 수익 HP를 먼저, 부족분은 충전 HP를 쓴다. 강의·출연 보류(BillingPort.hold)는 모두 이 종류다 */
    MIXED,
    /** 충전 HP만 쓴다(충전 원금 FIFO 배분). 충전 HP 환불 보류다 */
    CHARGE_ONLY,
    /** 수익 HP만 쓴다. 수익 HP 환전 보류다 */
    REVENUE_ONLY
}
