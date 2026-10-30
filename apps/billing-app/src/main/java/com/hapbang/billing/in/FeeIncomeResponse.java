package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.FeeIncomeResult;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자용 환전 수수료 수입 내역 한 건 (#336)
public record FeeIncomeResponse(
        @Schema(description = "수수료 수입 원장 ID")
        Long ledgerId,
        Long exchangeId,
        @Schema(description = "수수료 수입(HP), 환전 신청 시 확정")
        long feeAmount,
        @Schema(description = "환전 신청 금액(수익 HP) = 송금액 + 수수료")
        long requestedAmount,
        @Schema(description = "송금액(원)")
        long payoutAmount,
        @Schema(description = "수수료 수입을 기록한 시각(환전 송금 성공 처리 시각)")
        Instant recordedAt
) {

    static FeeIncomeResponse from(FeeIncomeResult result) {
        return new FeeIncomeResponse(result.ledgerId(), result.exchangeId(), result.feeAmountHp(),
                result.requestedAmountHp(), result.payoutAmountKrw(), result.recordedAt());
    }
}
