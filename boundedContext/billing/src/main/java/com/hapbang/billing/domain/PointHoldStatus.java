package com.hapbang.billing.domain;

// 보류 HP의 잔여 처리 상태와 최종 반환·지급·혼합 처리 결과를 구분한다.
public enum PointHoldStatus {
    HELD,
    PARTIALLY_PROCESSED,
    RELEASED,
    PAID,
    MIXED
}
