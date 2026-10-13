package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.SettlementSourceType;

// 지급 결과로, Billing 엔티티 대신 지급 건·출처 HP 종류별 지급액·충전 건별 소진과 재요청 여부를 담는다.
// 지급액·소진 내역은 지급을 처리한 당시의 값이고, 남은 보류액과 보류 상태는 응답 시점의 값이다.
public record PayoutResult(
        Long holdId,
        Long holdDetailId,
        Long settlementId,
        Long payerWalletId,
        Long receiverWalletId,
        BillingReferenceType referenceType,
        Long referenceId,
        SettlementSourceType sourceType,
        Long sourceId,
        PayoutType payoutType,
        long paidHp,
        long paidRevenueHp,
        long paidChargeHp,
        List<ChargeConsumption> chargeConsumptions,
        long remainingHeldHp,
        PointHoldStatus holdStatus,
        boolean replayed
) {

    // 충전 건별 소진 목록을 변경할 수 없는 복사본으로 보관한다.
    public PayoutResult {
        chargeConsumptions = List.copyOf(chargeConsumptions);
    }

    // 지급 상세·지급 건과 그 지급의 충전 건별 소진, 현재 보류 상태로 결과를 만든다.
    static PayoutResult of(PointHold hold, PointHoldDetail detail, Settlement settlement,
            List<ChargeConsumption> chargeConsumptions, boolean replayed) {
        return new PayoutResult(hold.getId(), detail.getId(), settlement.getId(), hold.getWalletId(),
                settlement.getReceiverWalletId(), hold.getReferenceType(), hold.getReferenceId(),
                settlement.getSourceType(), settlement.getSourceId(), settlement.getPayoutType(), detail.totalAmountHp(),
                detail.getRevenueAmountHp(), detail.getChargeAmountHp(), chargeConsumptions, hold.heldTotalHp(),
                hold.getStatus(), replayed);
    }

    // 충전 건 하나에서 지급으로 소진한 원금이다. 소진한 순서(최초 배분 순서)로 담는다. 이 원금은 충전 건에 돌아가지 않는다.
    public record ChargeConsumption(Long chargeId, long amountHp) {
    }
}
