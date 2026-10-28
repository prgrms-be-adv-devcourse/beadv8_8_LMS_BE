package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointHoldType;

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

    // 보류 종류에 따라 나눈다 (#241). MIXED는 위와 같고, CHARGE_ONLY는 충전 HP만, REVENUE_ONLY는 수익 HP만 쓴다.
    // 쓸 수 있는 HP 종류의 가용 잔액이 모자라면(다른 종류가 넉넉해도) INSUFFICIENT_BALANCE로 거절한다.
    static HoldSplit of(PointHoldType holdType, long availableRevenueHp, long availableChargeHp, long amountHp) {
        return switch (holdType) {
            case MIXED -> of(availableRevenueHp, availableChargeHp, amountHp);
            case CHARGE_ONLY -> of(0L, availableChargeHp, amountHp);
            case REVENUE_ONLY -> of(availableRevenueHp, 0L, amountHp);
        };
    }
}
