package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.app.PayoutResult.ChargeConsumption;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 확정 차감 결과 (#241). 이 요청이 차감한 HP 종류별 금액·지정한 충전 건·충전 건별 원금 소진은 처리 당시 값이고,
// 남은 보류액과 보류 상태는 응답 시점 값이다.
public record CaptureResult(
        Long holdId,
        Long holdDetailId,
        Long walletId,
        BillingReferenceType referenceType,
        Long referenceId,
        long capturedHp,
        long capturedChargeHp,
        long capturedRevenueHp,
        Long targetChargeId,
        List<ChargeConsumption> chargeConsumptions,
        long remainingHeldHp,
        PointHoldStatus holdStatus,
        boolean replayed
) {

    public CaptureResult {
        chargeConsumptions = List.copyOf(chargeConsumptions);
    }

    static CaptureResult of(PointHold hold, PointHoldDetail detail, List<ChargeConsumption> chargeConsumptions,
            boolean replayed) {
        return new CaptureResult(hold.getId(), detail.getId(), hold.getWalletId(), hold.getReferenceType(),
                hold.getReferenceId(), detail.totalAmountHp(), detail.getChargeAmountHp(), detail.getRevenueAmountHp(),
                detail.getTargetChargeId(), chargeConsumptions, hold.heldTotalHp(), hold.getStatus(), replayed);
    }
}
