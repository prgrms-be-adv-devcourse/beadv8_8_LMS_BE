package com.hapbang.billing.app;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionHoldStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.BudgetReleaseRepository;
import com.hapbang.billing.out.BudgetReleaseSum;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.LedgerReferenceSum;
import com.hapbang.billing.out.LedgerTransactionSum;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.PromotionHoldRepository;
import com.hapbang.billing.out.PromotionHoldSum;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 프로모션 지갑 불변식 검사. WalletReconciliationUseCase가 프로모션 지갑을 대사할 때 같은 트랜잭션(한 스냅숏) 안에서 부른다.
// 검사만 하고 지갑·원장·예약을 고치지 않는다.
// - P1_AVAILABLE: 가용 칸 = Σ PROMOTION_FUND − Σ PROMOTION_WITHDRAW − Σ BUDGET_RESERVE + Σ BUDGET_RELEASE(가용 칸 원장, 부호 그대로 합).
//   이 네 유형만 더하는 것은 의도다. 다른 유형(역분개 REVERSAL 등)이 프로모션 가용 칸에 원장을 쓰면 칸 대조는 맞고 P1_AVAILABLE만
//   불일치로 잡힌다. 프로모션 가용 칸에 다른 유형의 원장을 쓰는 기능을 더하면 이 식도 함께 고친다
// - P1_AVAILABLE_NON_NEGATIVE: 가용 칸 ≥ 0
// - P1_EARMARK: Σ 예약 earmark(총액 − 해제됨) ≤ 순 충전(충전 − 회수)
// - P3_RESERVED: 예약 칸 = Σ 남은 예산, P3_HELD: 보류 칸 = Σ 보류 중
// - 예약 하나마다(P2의 원장 부분): 예약 원장(예약 칸 +) = 총액, 해제 원장(가용 칸 +) 합 = 해제됨, 해제 기록 합 = 해제됨,
//   보류 중(HELD)인 프로모션 보류 합 = 보류 중.
//   맞지 않는 예약만 "P2_… reservationId=…" 항목으로 더한다.
@Component
@RequiredArgsConstructor
class PromotionWalletReconciliation {

    static final String P1_AVAILABLE = "P1_AVAILABLE";
    static final String P1_AVAILABLE_NON_NEGATIVE = "P1_AVAILABLE_NON_NEGATIVE";
    static final String P1_EARMARK = "P1_EARMARK";
    static final String P3_RESERVED = "P3_RESERVED";
    static final String P3_HELD = "P3_HELD";
    static final String P2_RESERVE_LEDGER = "P2_RESERVE_LEDGER";
    static final String P2_RELEASE_LEDGER = "P2_RELEASE_LEDGER";
    static final String P2_RELEASE_RECORDS = "P2_RELEASE_RECORDS";
    static final String P2_HOLD_RECORDS = "P2_HOLD_RECORDS";

    static final String P2_PAID_RECORDS = "P2_PAID_RECORDS";
    static final String P2_HELD_LEDGER = "P2_HELD_LEDGER";

    private final PointLedgerRepository pointLedgerRepository;
    private final BudgetReservationRepository budgetReservationRepository;
    private final BudgetReleaseRepository budgetReleaseRepository;
    private final PromotionHoldRepository promotionHoldRepository;

    List<WalletReconciliation.InvariantCheck> check(Wallet wallet) {
        Long walletId = wallet.getId();
        Map<LedgerTransactionType, Long> available = availableByType(walletId);
        long fund = available.getOrDefault(LedgerTransactionType.PROMOTION_FUND, 0L);
        long withdraw = available.getOrDefault(LedgerTransactionType.PROMOTION_WITHDRAW, 0L);
        long reserve = available.getOrDefault(LedgerTransactionType.BUDGET_RESERVE, 0L);
        long release = available.getOrDefault(LedgerTransactionType.BUDGET_RELEASE, 0L);
        long availableHp = wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE);

        List<BudgetReservation> reservations = budgetReservationRepository.findAllByWalletId(walletId);
        long remaining = 0L;
        long held = 0L;
        long earmarked = 0L;
        for (BudgetReservation reservation : reservations) {
            remaining = Math.addExact(remaining, reservation.getRemainingHp());
            held = Math.addExact(held, reservation.getHeldHp());
            earmarked = Math.addExact(earmarked,
                    Math.subtractExact(reservation.getAmountHp(), reservation.getReleasedHp()));
        }

        List<WalletReconciliation.InvariantCheck> checks = new ArrayList<>();
        // 회수·예약 원장은 음수, 충전·해제 원장은 양수로 기록되므로 부호 그대로 더하면 초안 P1 식(회수 포함)이다
        checks.add(WalletReconciliation.InvariantCheck.equal(P1_AVAILABLE, availableHp,
                Math.addExact(Math.addExact(fund, withdraw), Math.addExact(reserve, release))));
        checks.add(WalletReconciliation.InvariantCheck.atMost(P1_AVAILABLE_NON_NEGATIVE, 0L, availableHp));
        checks.add(WalletReconciliation.InvariantCheck.atMost(P1_EARMARK, earmarked, Math.addExact(fund, withdraw)));
        checks.add(WalletReconciliation.InvariantCheck.equal(P3_RESERVED,
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.RESERVED), remaining));
        checks.add(WalletReconciliation.InvariantCheck.equal(P3_HELD,
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.HELD), held));
        checks.addAll(reservationChecks(walletId, reservations));
        return checks;
    }

    private Map<LedgerTransactionType, Long> availableByType(Long walletId) {
        Map<LedgerTransactionType, Long> sums = new EnumMap<>(LedgerTransactionType.class);
        for (LedgerTransactionSum sum : pointLedgerRepository.sumAmountByTransactionType(walletId,
                PointType.PROMOTION, BalanceBucket.AVAILABLE)) {
            sums.put(sum.transactionType(), sum.amountHp());
        }
        return sums;
    }

    // 예약마다 예약·해제 원장 합과 해제 기록 합을 예약 행과 대조하고, 맞지 않는 예약만 돌려준다.
    private List<WalletReconciliation.InvariantCheck> reservationChecks(Long walletId,
            List<BudgetReservation> reservations) {
        Map<Long, Long> reserveLedger = new HashMap<>();
        Map<Long, Long> releaseLedger = new HashMap<>();
        Map<Long, Long> holdLedger = new HashMap<>();
        Map<Long, Long> restoreLedger = new HashMap<>();
        for (LedgerReferenceSum sum : pointLedgerRepository.sumAmountByReference(walletId,
                BillingReferenceType.COUPON_BUDGET)) {
            if (sum.transactionType() == LedgerTransactionType.BUDGET_RESERVE
                    && sum.balanceBucket() == BalanceBucket.RESERVED) {
                reserveLedger.put(sum.referenceId(), sum.amountHp());
            } else if (sum.transactionType() == LedgerTransactionType.BUDGET_RELEASE
                    && sum.balanceBucket() == BalanceBucket.AVAILABLE) {
                releaseLedger.put(sum.referenceId(), sum.amountHp());
            } else if (sum.transactionType() == LedgerTransactionType.PROMOTION_HOLD
                    && sum.balanceBucket() == BalanceBucket.HELD) {
                holdLedger.put(sum.referenceId(), sum.amountHp());
            } else if (sum.transactionType() == LedgerTransactionType.PROMOTION_RESTORE
                    && sum.balanceBucket() == BalanceBucket.HELD) {
                restoreLedger.put(sum.referenceId(), sum.amountHp());
            }
        }
        Map<Long, Long> releaseRecords = new HashMap<>();
        for (BudgetReleaseSum sum : budgetReleaseRepository.sumAmountByReservation(walletId)) {
            releaseRecords.put(sum.budgetReservationId(), sum.amountHp());
        }

        Map<Long, Long> heldRecords = new HashMap<>();
        for (PromotionHoldSum sum : promotionHoldRepository.sumAmountByReservation(walletId, PromotionHoldStatus.HELD)) {
            heldRecords.put(sum.budgetReservationId(), sum.amountHp());
        }

        Map<Long, Long> paidRecords = new HashMap<>();
        for (PromotionHoldSum sum : promotionHoldRepository.sumAmountByReservation(walletId, PromotionHoldStatus.PAID)) {
            paidRecords.put(sum.budgetReservationId(), sum.amountHp());
        }
        List<WalletReconciliation.InvariantCheck> mismatched = new ArrayList<>();
        for (BudgetReservation reservation : reservations) {
            Long id = reservation.getId();
            String suffix = " reservationId=" + id;
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_PAID_RECORDS + suffix,
                    paidRecords.getOrDefault(id, 0L), reservation.getPaidHp()));
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_HELD_LEDGER + suffix,
                    Math.subtractExact(Math.addExact(holdLedger.getOrDefault(id, 0L),
                            restoreLedger.getOrDefault(id, 0L)), paidRecords.getOrDefault(id, 0L)),
                    reservation.getHeldHp()));
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_RESERVE_LEDGER + suffix,
                    reserveLedger.getOrDefault(reservation.getId(), 0L), reservation.getAmountHp()));
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_RELEASE_LEDGER + suffix,
                    releaseLedger.getOrDefault(reservation.getId(), 0L), reservation.getReleasedHp()));
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_RELEASE_RECORDS + suffix,
                    releaseRecords.getOrDefault(reservation.getId(), 0L), reservation.getReleasedHp()));
            addIfMismatched(mismatched, WalletReconciliation.InvariantCheck.equal(P2_HOLD_RECORDS + suffix,
                    heldRecords.getOrDefault(reservation.getId(), 0L), reservation.getHeldHp()));
        }
        return mismatched;
    }

    private static void addIfMismatched(List<WalletReconciliation.InvariantCheck> checks,
            WalletReconciliation.InvariantCheck check) {
        if (!check.matched()) {
            checks.add(check);
        }
    }
}
