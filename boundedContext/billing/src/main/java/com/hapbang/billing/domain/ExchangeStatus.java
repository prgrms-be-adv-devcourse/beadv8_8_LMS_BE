package com.hapbang.billing.domain;

// 수익 HP 환전 요청의 진행 상태를 구분하며 외부 처리 결과가 불명확하면 UNKNOWN으로 표현한다.
public enum ExchangeStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN
}
