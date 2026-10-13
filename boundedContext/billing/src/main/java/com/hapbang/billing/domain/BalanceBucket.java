package com.hapbang.billing.domain;

// HP 잔액이 사용 가능한 가용 영역인지 거래 중인 보류 영역인지 구분한다.
public enum BalanceBucket {
    /** 가용 잔액. 결제·환불·환전 신청에 쓸 수 있는 HP */
    AVAILABLE,
    /** 보류 잔액. 거래 중이라 묶어 둔 HP. 지급되면 받는 쪽으로 가고, 반환되면 같은 HP 종류의 가용 잔액으로 돌아온다 */
    HELD
}
