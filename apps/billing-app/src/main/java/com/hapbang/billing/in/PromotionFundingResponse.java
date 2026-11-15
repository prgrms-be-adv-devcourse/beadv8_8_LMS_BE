package com.hapbang.billing.in;

import com.hapbang.billing.app.PromotionFundingResult;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 프로모션 지갑 충전 응답
public record PromotionFundingResponse(
        String fundingWorkId,
        @Schema(description = "충전 금액(HP). 재요청이어도 처음 기록 그대로다")
        long amountHp,
        @Schema(description = "응답 시점의 프로모션 지갑 가용 잔액(HP, 쿠폰 예산으로 묶지 않은 HP)")
        long availableHp,
        @Schema(description = "같은 충전 작업 ID의 재요청이라 새로 충전하지 않고 처음 결과를 돌려줬으면 true")
        boolean replayed
) {

    static PromotionFundingResponse from(PromotionFundingResult result) {
        return new PromotionFundingResponse(result.fundingWorkId(), result.amountHp(), result.availableHp(),
                result.replayed());
    }
}
