package com.hapbang.coupon.domain;

/** 프로모션 예산 작업 상태. 결과를 모르면 PENDING으로 두고 같은 작업 ID로 다시 조회·호출한다 */
public enum BudgetWorkStatus {
    PENDING,
    DONE,
    FAILED
}
