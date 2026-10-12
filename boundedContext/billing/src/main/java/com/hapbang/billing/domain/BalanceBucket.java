package com.hapbang.billing.domain;

// HP 잔액이 사용 가능한 가용 영역인지 거래 중인 보류 영역인지 구분한다.
public enum BalanceBucket {
    AVAILABLE,
    HELD
}
