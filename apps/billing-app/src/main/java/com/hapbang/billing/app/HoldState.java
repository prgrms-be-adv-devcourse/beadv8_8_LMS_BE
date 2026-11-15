package com.hapbang.billing.app;

import java.util.List;

import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.SettlementStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 참조 대상 하나의 지갑 기록 (#577, 서비스 간 HP 대사의 조회 결과). in 계층이 shared HoldStateDto로 바꾼다(BD-129).
// 지갑에 기록이 없으면 hold·voided가 null이고 settlements가 비어 있다.
public record HoldState(
        BillingReferenceType referenceType,
        Long referenceId,
        Hold hold,
        Voided voided,
        List<Settlement> settlements,
        List<Promotion> promotions,
        PromotionAmount promotion
) {

    public HoldState {
        settlements = List.copyOf(settlements);
        promotions = List.copyOf(promotions);
    }

    public HoldState(BillingReferenceType referenceType, Long referenceId, Hold hold, Voided voided,
            List<Settlement> settlements) {
        this(referenceType, referenceId, hold, voided, settlements, List.of(), null);
    }

    public record Promotion(Long couponId, Long memberCouponId, long generation, Long reservationId,
            long amountHp, String status) {
    }

    public record PromotionAmount(long held, long paid, long restored) {
    }

    // 보류. HP 종류마다 original = remaining + released + paid + captured 다
    public record Hold(
            PointHoldStatus status,
            Long payerUserId,
            Amount original,
            Amount remaining,
            Amount released,
            Amount paid,
            Amount captured
    ) {
    }

    public record Amount(long chargeHp, long revenueHp) {

        static final Amount ZERO = new Amount(0, 0);

        public long totalHp() {
            return Math.addExact(chargeHp, revenueHp);
        }
    }

    public record Voided(HoldVoidOutcome outcome, long releasedHp) {
    }

    public record Settlement(
            SettlementSourceType sourceType,
            Long sourceId,
            PayoutType payoutType,
            Long receiverUserId,
            long grossAmountHp,
            long paidAmountHp,
            SettlementStatus status,
            Long buyerPaidHp,
            Long promotionPaidHp
    ) {
        public Settlement(SettlementSourceType sourceType, Long sourceId, PayoutType payoutType,
                Long receiverUserId, long grossAmountHp, long paidAmountHp, SettlementStatus status) {
            this(sourceType, sourceId, payoutType, receiverUserId, grossAmountHp, paidAmountHp, status, null, null);
        }
    }
}
