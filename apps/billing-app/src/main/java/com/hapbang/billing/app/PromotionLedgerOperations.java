package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionHold;
import com.hapbang.billing.domain.PromotionFunding;
import com.hapbang.billing.domain.PromotionWithdrawal;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 프로모션 지갑의 충전·회수·예산 예약·예산 해제를 원장 기록 요청(LedgerOperation)으로 만든다.
// 칸 이동은 여기서만 정한다: 충전 가용 +, 회수 가용 −, 예약 가용 − / 예약 +, 해제 예약 − / 가용 +, 프로모션 보류 예약 − / 보류 +. 기록은 LedgerRecordUseCase가 하므로
// 다른 원장과 같이 지갑 락 아래에서 캐시 잔액이 바뀌고, 칸이 음수가 되면 INSUFFICIENT_BALANCE로 거절된다(아무것도 바뀌지 않는다).
// 예약되지 않은 HP만 회수·예약할 수 있는 것은 가용 칸에서만 빼기 때문이다(예약·보류 칸은 건드리지 않는다).
final class PromotionLedgerOperations {

    private PromotionLedgerOperations() {
    }

    // 충전: 프로모션 HP 가용 칸 +금액. 참조 대상은 충전 기록이다.
    static LedgerOperation fund(PromotionFunding funding, Instant occurredAt) {
        Objects.requireNonNull(funding.getId(), "저장한 충전 기록만 원장에 기록합니다.");
        return new LedgerOperation(BillingKeys.promotionFund(funding.getId()), LedgerTransactionType.PROMOTION_FUND,
                BillingReferenceType.PROMOTION_FUNDING, funding.getId(), null, occurredAt,
                List.of(LedgerEntry.of(funding.getWalletId(), PointType.PROMOTION, BalanceBucket.AVAILABLE,
                        funding.getAmountHp())));
    }

    // 회수: 프로모션 HP 가용 칸 −금액. 참조 대상은 회수 기록이다.
    static LedgerOperation withdraw(PromotionWithdrawal withdrawal, Instant occurredAt) {
        Objects.requireNonNull(withdrawal.getId(), "저장한 회수 기록만 원장에 기록합니다.");
        return new LedgerOperation(BillingKeys.promotionWithdraw(withdrawal.getId()),
                LedgerTransactionType.PROMOTION_WITHDRAW, BillingReferenceType.PROMOTION_WITHDRAWAL, withdrawal.getId(),
                null, occurredAt, List.of(LedgerEntry.of(withdrawal.getWalletId(), PointType.PROMOTION,
                        BalanceBucket.AVAILABLE, -withdrawal.getAmountHp())));
    }

    // 예산 예약: 프로모션 HP 가용 → 예약으로 예약 총액만큼 옮긴다. 참조 대상은 예약이다.
    static LedgerOperation reserve(BudgetReservation reservation, Instant occurredAt) {
        Objects.requireNonNull(reservation.getId(), "저장한 예산 예약만 원장에 기록합니다.");
        return new LedgerOperation(BillingKeys.budgetReserve(reservation.getId()),
                LedgerTransactionType.BUDGET_RESERVE, BillingReferenceType.COUPON_BUDGET, reservation.getId(), null,
                occurredAt, LedgerEntry.move(reservation.getWalletId(), PointType.PROMOTION, BalanceBucket.AVAILABLE,
                        BalanceBucket.RESERVED, reservation.getAmountHp()));
    }

    // 프로모션 보류: 주문 항목의 플랫폼 몫을 프로모션 HP 예약 → 보류로 옮긴다. 참조 대상은 그 쿠폰의 예약이고 작업 키는 프로모션 보류마다 다르다.
    static LedgerOperation promotionHold(Long promotionWalletId, PromotionHold promotionHold, Instant occurredAt) {
        Objects.requireNonNull(promotionHold.getId(), "저장한 프로모션 보류만 원장에 기록합니다.");
        return new LedgerOperation(BillingKeys.promotionHold(promotionHold.getId()),
                LedgerTransactionType.PROMOTION_HOLD, BillingReferenceType.COUPON_BUDGET,
                promotionHold.getBudgetReservationId(), null, occurredAt,
                LedgerEntry.move(promotionWalletId, PointType.PROMOTION, BalanceBucket.RESERVED, BalanceBucket.HELD,
                        promotionHold.getAmountHp()));
    }

    static LedgerOperation promotionRestore(Long promotionWalletId, PromotionHold hold, Instant occurredAt) {
        return new LedgerOperation(BillingKeys.promotionRestore(hold.getId()),
                LedgerTransactionType.PROMOTION_RESTORE, BillingReferenceType.COUPON_BUDGET,
                hold.getBudgetReservationId(), null, occurredAt,
                LedgerEntry.move(promotionWalletId, PointType.PROMOTION, BalanceBucket.HELD,
                        BalanceBucket.RESERVED, hold.getAmountHp()));
    }

    // 예산 해제: 프로모션 HP 예약 → 가용으로 해제 금액만큼 옮긴다. 참조 대상은 해제한 예약이고, 작업 키는 해제 기록마다 다르다.
    static LedgerOperation release(BudgetReservation reservation, BudgetRelease release, Instant occurredAt) {
        Objects.requireNonNull(release.getId(), "저장한 해제 기록만 원장에 기록합니다.");
        if (!Objects.equals(reservation.getId(), release.getBudgetReservationId())) {
            throw new IllegalArgumentException("해제 기록의 예산 예약이 다릅니다.");
        }
        return new LedgerOperation(BillingKeys.budgetRelease(release.getId()), LedgerTransactionType.BUDGET_RELEASE,
                BillingReferenceType.COUPON_BUDGET, reservation.getId(), null, occurredAt,
                LedgerEntry.move(reservation.getWalletId(), PointType.PROMOTION, BalanceBucket.RESERVED,
                        BalanceBucket.AVAILABLE, release.getAmountHp()));
    }
}
