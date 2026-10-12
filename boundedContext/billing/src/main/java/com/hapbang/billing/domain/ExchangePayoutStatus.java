package com.hapbang.billing.domain;

// 환전 송금의 진행 상태를 구분하며 외부 응답만으로 결과를 확정할 수 없으면 UNKNOWN으로 표현한다.
public enum ExchangePayoutStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN
}
