package com.hapbang.billing.domain;

// 보류에 배분된 충전 원금의 잔여 처리 상태와 최종 소진·반환·혼합 처리 결과를 구분한다.
public enum PointChargeUsageStatus {
    /** 보류에 배분한 충전 원금 전부가 보류 중이다 */
    HELD,
    /** 일부가 지급(소진)·반환되었고 남은 보류가 있다 */
    PARTIALLY_PROCESSED,
    /** 남은 보류가 없고 전부 지급(소진)되었다. 원금은 돌아오지 않는다 */
    CONSUMED,
    /** 남은 보류가 없고 전부 반환되어 원래 충전 건의 가용 원금으로 되돌아갔다 */
    RELEASED,
    /** 남은 보류가 없고 지급(소진)과 반환이 함께 있었다 */
    MIXED
}
