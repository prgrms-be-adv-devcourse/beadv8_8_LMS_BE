package com.hapbang.billing.domain;

// 보류에 배분된 충전 원금의 잔여 처리 상태와 최종 소진·반환·혼합 처리 결과를 구분한다.
public enum PointChargeUsageStatus {
    HELD,
    PARTIALLY_PROCESSED,
    CONSUMED,
    RELEASED,
    MIXED
}
