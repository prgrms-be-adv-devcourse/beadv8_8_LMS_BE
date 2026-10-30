package com.hapbang.billing.in;

import com.hapbang.billing.app.PlatformWalletResult;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자용 플랫폼 지갑 응답 (#336)
public record PlatformWalletResponse(
        Long walletId,
        @Schema(description = "수수료 수입 잔액(HP). 송금 완료된 환전의 수수료 합이다")
        long feeIncomeBalance
) {

    static PlatformWalletResponse from(PlatformWalletResult result) {
        return new PlatformWalletResponse(result.walletId(), result.feeIncomeHp());
    }
}
