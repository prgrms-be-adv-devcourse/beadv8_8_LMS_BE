package com.hapbang.billing.domain;

// 수익 HP 지급의 예정·중단·처리 중·완료·실패 상태를 구분한다.
public enum SettlementStatus {
    SCHEDULED,
    SUSPENDED,
    PROCESSING,
    COMPLETED,
    FAILED
}
