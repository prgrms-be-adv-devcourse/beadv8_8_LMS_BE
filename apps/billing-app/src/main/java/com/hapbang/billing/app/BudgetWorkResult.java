package com.hapbang.billing.app;

import com.hapbang.shared.billing.domain.PromotionBudgetWorkKind;
import com.hapbang.shared.billing.domain.PromotionBudgetWorkStatus;

// 쿠폰 예산 작업 조회 결과. 기록이 없으면 NOT_FOUND이고 종류·예약·금액이 없다.
public record BudgetWorkResult(String workId, PromotionBudgetWorkKind kind, PromotionBudgetWorkStatus status,
        Long reservationId, Long amountHp) {

    static BudgetWorkResult notFound(String workId) {
        return new BudgetWorkResult(workId, null, PromotionBudgetWorkStatus.NOT_FOUND, null, null);
    }

    static BudgetWorkResult done(String workId, PromotionBudgetWorkKind kind, Long reservationId, long amountHp) {
        return new BudgetWorkResult(workId, kind, PromotionBudgetWorkStatus.DONE, reservationId, amountHp);
    }
}
