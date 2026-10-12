package com.hapbang.billing.domain;

// 개별 충전 건에 대한 PG 환불의 진행 상태를 구분하며 결과가 불명확하면 UNKNOWN으로 표현한다.
public enum PointRefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN
}
