package com.hapbang.billing.in;

import com.hapbang.billing.app.PromotionWalletResult;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자용 프로모션 지갑 칸별 잔액 응답
public record PromotionWalletResponse(
        Long walletId,
        @Schema(description = "가용 잔액(HP). 쿠폰 예산으로 묶지 않은 HP이며 예산 예약·관리자 회수는 여기서만 뺀다")
        long availableHp,
        @Schema(description = "예약 잔액(HP). 쿠폰 예산으로 묶었지만 아직 주문 항목에 보류하지 않은 HP(쿠폰별 남은 예산의 합)")
        long reservedHp,
        @Schema(description = "보류 잔액(HP). 주문 항목에 보류한 쿠폰 예산")
        long heldHp
) {

    static PromotionWalletResponse from(PromotionWalletResult result) {
        return new PromotionWalletResponse(result.walletId(), result.availableHp(), result.reservedHp(),
                result.heldHp());
    }
}
