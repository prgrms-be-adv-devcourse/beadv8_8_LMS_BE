package com.hapbang.billing.domain;

// HP의 종류를 회원의 충전 HP·수익 HP와 플랫폼의 수수료 수입으로 구분한다.
public enum PointType {
    /** 충전 HP. 현금으로 충전한 예치금이다. 환불할 수 있고 환전할 수 없다. HP 결제 때 수익 HP를 다 쓴 뒤에 쓴다 */
    CHARGE,
    /** 수익 HP. 강의 판매·출연료로 지급받은 HP다. 환전할 수 있고 환불할 수 없다. HP 결제 때 먼저 쓴다 */
    REVENUE,
    /** 수수료 수입. 환전 수수료처럼 플랫폼이 가져가는 HP다. 플랫폼 지갑의 가용 칸에만 있고 결제·환불·환전에 쓰지 않는다 (#336) */
    FEE
}
