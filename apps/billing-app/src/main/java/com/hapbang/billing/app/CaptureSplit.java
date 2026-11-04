package com.hapbang.billing.app;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointHoldType;

// 확정 차감 금액을 HP 종류별로 나눈 결과다 (#241). 확정 차감은 종류가 하나인 보류(CHARGE_ONLY·REVENUE_ONLY)에서만 하므로
// 그 종류의 남은 보류에서만 차감한다.
record CaptureSplit(long chargeHp, long revenueHp) {

    // 보류 종류의 남은 보류에서 금액을 차감한다. 0 이하이면 INVALID_POINT_AMOUNT, 남은 보류보다 많으면 CAPTURE_AMOUNT_EXCEEDS_HELD,
    // MIXED 보류(강의·출연)는 확정 차감하지 않으므로 HOLD_TYPE_NOT_SUPPORTED로 거절한다.
    static CaptureSplit of(PointHoldType holdType, long heldChargeHp, long heldRevenueHp, long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        return switch (holdType) {
            case CHARGE_ONLY -> {
                if (amountHp > heldChargeHp) {
                    throw new BillingException(BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD);
                }
                yield new CaptureSplit(amountHp, 0L);
            }
            case REVENUE_ONLY -> {
                if (amountHp > heldRevenueHp) {
                    throw new BillingException(BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD);
                }
                yield new CaptureSplit(0L, amountHp);
            }
            case MIXED -> throw new BillingException(BillingErrorCode.HOLD_TYPE_NOT_SUPPORTED);
        };
    }

    long totalHp() {
        return Math.addExact(chargeHp, revenueHp);
    }
}
