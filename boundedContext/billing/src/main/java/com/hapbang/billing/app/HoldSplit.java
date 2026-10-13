package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 보류 금액을 수익 HP와 충전 HP로 나눈 결과다. 수익 HP를 먼저 쓰고 부족한 만큼만 충전 HP를 쓴다.
record HoldSplit(long revenueHp, long chargeHp) {

    // 가용 수익 HP를 전부 먼저 배분하고 나머지를 가용 충전 HP에서 배분하며, 둘을 합쳐도 모자라면 INSUFFICIENT_BALANCE로 거절한다.
    static HoldSplit of(long availableRevenueHp, long availableChargeHp, long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        long revenueHp = Math.min(availableRevenueHp, amountHp);
        long chargeHp = amountHp - revenueHp;
        if (chargeHp > availableChargeHp) {
            throw new BillingException(BillingErrorCode.INSUFFICIENT_BALANCE);
        }
        return new HoldSplit(revenueHp, chargeHp);
    }
}
