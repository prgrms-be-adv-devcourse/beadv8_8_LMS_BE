package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 지급 금액을 출처 HP 종류별로 나눈 결과다. 남은 수익 HP 보류를 먼저 지급하고 부족한 만큼만 충전 HP 보류에서 지급한다.
record PayoutSplit(long revenueHp, long chargeHp) {

    // 남은 수익 HP 보류를 전부 먼저 배분하고 나머지를 남은 충전 HP 보류에서 배분하며, 둘을 합쳐도 모자라면 PAYOUT_AMOUNT_EXCEEDS_HELD로 거절한다.
    static PayoutSplit of(long heldRevenueHp, long heldChargeHp, long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        long revenueHp = Math.min(heldRevenueHp, amountHp);
        long chargeHp = amountHp - revenueHp;
        if (chargeHp > heldChargeHp) {
            throw new BillingException(BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
        }
        return new PayoutSplit(revenueHp, chargeHp);
    }

    // 지급 총액(받는 쪽 수익 HP 증가액)
    long totalHp() {
        return Math.addExact(revenueHp, chargeHp);
    }
}
