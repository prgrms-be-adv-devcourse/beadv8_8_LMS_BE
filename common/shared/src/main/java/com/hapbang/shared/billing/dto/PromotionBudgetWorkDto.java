package com.hapbang.shared.billing.dto;

import com.hapbang.shared.billing.domain.PromotionBudgetWorkKind;
import com.hapbang.shared.billing.domain.PromotionBudgetWorkStatus;

// 쿠폰 예산 작업 조회 결과(응답 유실 복구용). status가 NOT_FOUND면 kind·reservationId·amountHp는 없고, 같은 workId로 다시 보내면 된다.
// DONE이면 예약은 예약 총액, 해제는 해제 금액이 amountHp다.
public record PromotionBudgetWorkDto(
        String workId,
        PromotionBudgetWorkKind kind,
        PromotionBudgetWorkStatus status,
        String reservationId,
        Long amountHp
) {
}
