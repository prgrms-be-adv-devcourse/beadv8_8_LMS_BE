package com.hapbang.shared.billing.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

// 참조 대상 하나의 지갑 기록 (#577, 서비스 간 HP 대사). 업무 상태와 맞는지는 호출한 모듈이 판단한다.
// 지갑에 기록이 없으면 hold·voided가 null이고 settlements가 비어 있다(요청한 ID는 모두 응답한다).
// 지갑·보류·원장 ID와 충전 건별 배분은 담지 않는다. HP 종류별 금액은 대사에 한해 담는다(BD-137).
public record HoldStateDto(
        BillingReferenceType referenceType,
        Long referenceId,
        Hold hold,
        Voided voided,
        List<Settlement> settlements,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Promotion> promotions,
        @JsonInclude(JsonInclude.Include.NON_NULL) PromotionAmount promotion
) {

    public HoldStateDto {
        settlements = List.copyOf(settlements);
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
    }

    public HoldStateDto(BillingReferenceType referenceType, Long referenceId, Hold hold, Voided voided,
            List<Settlement> settlements) {
        this(referenceType, referenceId, hold, voided, settlements, List.of(), null);
    }

    public record Promotion(Long couponId, Long memberCouponId, long generation, String reservationId,
            long amountHp, String status) {
    }

    public record PromotionAmount(long held, long paid, long restored) {
    }

    // 보류. original = remaining + released + paid + captured 가 HP 종류마다 성립한다.
    // payerUserId는 보류한 회원, 금액은 보류 생성 값·남은 보류와 처리 건(반환·지급·확정 차감) 합이다.
    public record Hold(
            BillingHoldStatus status,
            Long payerUserId,
            Amount original,
            Amount remaining,
            Amount released,
            Amount paid,
            Amount captured
    ) {
    }

    // HP 종류별 금액과 합계
    public record Amount(
            long chargeHp,
            long revenueHp,
            long totalHp
    ) {
    }

    // 보류 무효화 기록(#479). releasedHp는 무효화가 반환한 금액이다(RELEASED일 때만 0보다 크다)
    public record Voided(
            HoldVoidOutcome outcome,
            long releasedHp
    ) {
    }

    // 이 보류에서 나간 지급 건(상태와 관계없이 전부)
    public record Settlement(
            SettlementSourceType sourceType,
            Long sourceId,
            PayoutType payoutType,
            Long receiverUserId,
            long grossAmountHp,
            long paidAmountHp,
            BillingSettlementStatus status,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long buyerPaidHp,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long promotionPaidHp
    ) {
        public Settlement(SettlementSourceType sourceType, Long sourceId, PayoutType payoutType,
                Long receiverUserId, long grossAmountHp, long paidAmountHp, BillingSettlementStatus status) {
            this(sourceType, sourceId, payoutType, receiverUserId, grossAmountHp, paidAmountHp, status, null, null);
        }
    }
}
