package com.hapbang.billing.domain;

// 여러 충전 건을 묶은 환불 요청의 전체 진행 상태와 부분 완료·실패·결과 불명확 상태를 구분한다.
public enum PointRefundRequestStatus {
    PENDING,
    PROCESSING,
    PARTIALLY_COMPLETED,
    COMPLETED,
    FAILED,
    UNKNOWN
}
