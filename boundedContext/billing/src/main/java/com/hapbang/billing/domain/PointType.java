package com.hapbang.billing.domain;

// HP의 종류를 충전 HP와 수익 HP로 구분한다.
public enum PointType {
    /** 충전 HP. 현금으로 충전한 예치금이다. 환불할 수 있고 환전할 수 없다. HP 결제 때 수익 HP를 다 쓴 뒤에 쓴다 */
    CHARGE,
    /** 수익 HP. 강의 판매·출연료로 지급받은 HP다. 환전할 수 있고 환불할 수 없다. HP 결제 때 먼저 쓴다 */
    REVENUE
}
