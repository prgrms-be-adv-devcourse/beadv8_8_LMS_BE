package com.hapbang.shared.billing.domain;

// 쿠폰 예산 작업 조회에서 그 작업 ID가 어떤 작업이었는지 구분한다.
public enum PromotionBudgetWorkKind {
    /** 예산 예약(프로모션 지갑 가용 → 예약) */
    RESERVE,
    /** 예산 해제(예약의 남은 예산 → 프로모션 지갑 가용) */
    RELEASE
}
