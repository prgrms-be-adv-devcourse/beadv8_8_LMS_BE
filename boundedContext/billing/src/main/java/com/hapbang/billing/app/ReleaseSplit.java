package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 반환 금액을 충전 HP와 수익 HP로 나눈 결과다. 남은 충전 HP 보류를 먼저 반환하고 부족한 만큼만 수익 HP를 반환한다.
record ReleaseSplit(long chargeHp, long revenueHp) {

    // 남은 충전 HP 보류를 전부 먼저 배분하고 나머지를 남은 수익 HP 보류에서 배분하며, 둘을 합쳐도 모자라면 RELEASE_AMOUNT_EXCEEDS_HELD로 거절한다.
    static ReleaseSplit of(long heldChargeHp, long heldRevenueHp, long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        long chargeHp = Math.min(heldChargeHp, amountHp);
        long revenueHp = amountHp - chargeHp;
        if (revenueHp > heldRevenueHp) {
            throw new BillingException(BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        }
        return new ReleaseSplit(chargeHp, revenueHp);
    }
}
