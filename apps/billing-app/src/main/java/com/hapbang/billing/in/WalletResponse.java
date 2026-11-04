package com.hapbang.billing.in;

import com.hapbang.billing.domain.Wallet;

// 내 지갑의 네 가지 캐시 잔액과 가용·보류 합계를 담는 응답이다.
public record WalletResponse(
        Long walletId,
        long chargeBalanceHp,
        long revenueBalanceHp,
        long heldChargeHp,
        long heldRevenueHp,
        long availableTotalHp,
        long heldTotalHp
) {

    // 지갑 캐시 잔액으로 응답을 만들고, 가용 합계(충전+수익)와 보류 합계(보류 충전+보류 수익)를 계산한다.
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getChargeBalanceHp(),
                wallet.getRevenueBalanceHp(),
                wallet.getHeldChargeHp(),
                wallet.getHeldRevenueHp(),
                Math.addExact(wallet.getChargeBalanceHp(), wallet.getRevenueBalanceHp()),
                Math.addExact(wallet.getHeldChargeHp(), wallet.getHeldRevenueHp()));
    }
}
