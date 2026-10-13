package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldDetail;
import com.hapbang.billing.domain.PointHoldStatus;

// 반환 결과로, Billing 엔티티 대신 이 반환 요청이 되돌린 HP 종류별 금액·충전 건별 원금 복원과 재요청 여부를 담는다.
// 반환액·복원 내역은 반환을 처리한 당시의 값이고, 남은 보류액과 보류 상태는 응답 시점의 값이다.
public record ReleaseResult(
        Long holdId,
        Long holdDetailId,
        Long walletId,
        BillingReferenceType referenceType,
        Long referenceId,
        long releasedHp,
        long releasedChargeHp,
        long releasedRevenueHp,
        List<ChargeRestoration> chargeRestorations,
        long remainingHeldHp,
        PointHoldStatus holdStatus,
        boolean replayed
) {

    // 충전 건별 원금 복원 목록을 변경할 수 없는 복사본으로 보관한다.
    public ReleaseResult {
        chargeRestorations = List.copyOf(chargeRestorations);
    }

    // 반환 상세와 그 반환의 충전 건별 원금 복원, 현재 보류 상태로 결과를 만든다.
    static ReleaseResult of(PointHold hold, PointHoldDetail detail, List<ChargeRestoration> chargeRestorations,
            boolean replayed) {
        return new ReleaseResult(hold.getId(), detail.getId(), hold.getWalletId(), hold.getReferenceType(),
                hold.getReferenceId(), detail.totalAmountHp(), detail.getChargeAmountHp(), detail.getRevenueAmountHp(),
                chargeRestorations, hold.heldTotalHp(), hold.getStatus(), replayed);
    }

    // 충전 건 하나에 되돌린 원금이다. 복원한 순서(최초 배분의 역순)로 담는다.
    public record ChargeRestoration(Long chargeId, long amountHp) {
    }
}
