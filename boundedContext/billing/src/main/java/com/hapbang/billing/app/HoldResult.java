package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.PointHold;

// 보류 결과로, Billing 엔티티 대신 보류 ID·HP 종류별 최초 보류액·충전 건별 배분과 재요청 여부를 담는다.
public record HoldResult(
        Long holdId,
        Long walletId,
        BillingReferenceType referenceType,
        Long referenceId,
        long totalHp,
        long revenueHp,
        long chargeHp,
        List<ChargeAllocation> chargeAllocations,
        boolean replayed
) {

    // 충전 건별 배분 목록을 변경할 수 없는 복사본으로 보관한다.
    public HoldResult {
        chargeAllocations = List.copyOf(chargeAllocations);
    }

    // 저장된 보류와 충전 건별 배분으로 결과를 만든다.
    static HoldResult of(PointHold hold, List<ChargeAllocation> chargeAllocations, boolean replayed) {
        return new HoldResult(hold.getId(), hold.getWallet().getId(), hold.getReferenceType(), hold.getReferenceId(),
                hold.originalTotalHp(), hold.getOriginalRevenueHp(), hold.getOriginalChargeHp(), chargeAllocations,
                replayed);
    }

    // 충전 건 하나에서 이 보류에 배분한 원금이다 (POINT_CHARGE_USAGE 한 행).
    public record ChargeAllocation(Long chargeId, long amountHp) {
    }
}
