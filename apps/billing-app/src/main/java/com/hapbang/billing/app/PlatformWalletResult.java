package com.hapbang.billing.app;

// 관리자용 플랫폼 지갑 잔액 (#336). 수수료 수입은 지갑 캐시(fee_balance_hp)이며 원장 합계와 대사한다.
public record PlatformWalletResult(Long walletId, long feeIncomeHp) {
}
