package com.hapbang.shared.billing.domain;

// 쿠폰 예산 작업 조회의 결과. 지갑은 거절한 작업을 저장하지 않으므로 기록이 없으면 NOT_FOUND이고, 같은 작업 ID로 다시 보내면 된다.
public enum PromotionBudgetWorkStatus {
    /** 지갑이 처리해 커밋했다 */
    DONE,
    /** 지갑에 이 작업 ID의 기록이 없다(받지 못했거나 거절해 저장하지 않았다) */
    NOT_FOUND
}
