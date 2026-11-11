package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PromotionHold;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류 결과로, Billing 엔티티 대신 보류 ID·HP 종류별 최초 보류액·충전 건별 배분과 재요청 여부를 담는다.
// totalHp는 구매자 몫이고, 플랫폼 몫(프로모션 보류)은 promotions·promotionHeldHp에 따로 담는다(없으면 빈 목록·0).
public record HoldResult(
        Long holdId,
        Long walletId,
        BillingReferenceType referenceType,
        Long referenceId,
        long totalHp,
        long revenueHp,
        long chargeHp,
        List<ChargeAllocation> chargeAllocations,
        List<Promotion> promotions,
        boolean replayed
) {

    // 목록을 변경할 수 없는 복사본으로 보관한다.
    public HoldResult {
        chargeAllocations = List.copyOf(chargeAllocations);
        promotions = List.copyOf(promotions);
    }

    // 저장된 보류와 충전 건별 배분으로 결과를 만든다(플랫폼 몫 없음).
    static HoldResult of(PointHold hold, List<ChargeAllocation> chargeAllocations, boolean replayed) {
        return of(hold, chargeAllocations, List.of(), replayed);
    }

    // 저장된 보류·충전 건별 배분·프로모션 보류(쿠폰 ID 오름차순)로 결과를 만든다.
    static HoldResult of(PointHold hold, List<ChargeAllocation> chargeAllocations, List<PromotionHold> promotionHolds,
            boolean replayed) {
        return new HoldResult(hold.getId(), hold.getWalletId(), hold.getReferenceType(), hold.getReferenceId(),
                hold.originalTotalHp(), hold.getOriginalRevenueHp(), hold.getOriginalChargeHp(), chargeAllocations,
                promotionHolds.stream().map(Promotion::from).toList(), replayed);
    }

    // 플랫폼 몫 합계
    public long promotionHeldHp() {
        return promotions.stream().mapToLong(Promotion::amountHp).sum();
    }

    // 충전 건 하나에서 이 보류에 배분한 원금이다 (POINT_CHARGE_USAGE 한 행).
    public record ChargeAllocation(Long chargeId, long amountHp) {
    }

    // 쿠폰 하나의 플랫폼 몫(프로모션 보류 한 행). reservationId는 그 쿠폰의 예산 예약 행 ID다.
    public record Promotion(Long couponId, Long memberCouponId, long generation, Long reservationId, long amountHp) {

        static Promotion from(PromotionHold hold) {
            return new Promotion(hold.getCouponId(), hold.getMemberCouponId(), hold.getGeneration(),
                    hold.getBudgetReservationId(), hold.getAmountHp());
        }
    }
}
