package com.hapbang.billing.domain;

// PG 충전의 대기·완료·실패 및 완료된 충전 건의 부분·전체 환불 상태를 구분한다.
public enum PointChargeStatus {
    PENDING,
    COMPLETED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED
}
