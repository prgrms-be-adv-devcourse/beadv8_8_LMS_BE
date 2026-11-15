package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointLedger;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFunding;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.PromotionWithdrawal;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 프로모션 지갑의 충전·회수·예산 예약·해제가 만드는 원장 줄(칸 이동·작업 키·참조 대상)과,
// 그 줄을 지갑에 반영했을 때 예약 합이 충전 합을 넘지 못하고 가용 칸이 음수가 되지 않는지를 DB 없이 검증한다.
class PromotionLedgerOperationsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final long WALLET_ID = 2L;

    @Test
    @DisplayName("충전은 프로모션 HP 가용 칸 +, 회수는 가용 칸 − 한 줄이고 충전·회수 기록을 참조한다")
    void fundAndWithdrawLines() {
        LedgerOperation fund = PromotionLedgerOperations.fund(funding(11L, 1_000L), NOW);
        LedgerOperation withdraw = PromotionLedgerOperations.withdraw(withdrawal(12L, 300L), NOW);

        assertThat(fund.operationKey()).isEqualTo("PROMOTION_FUND:11");
        assertThat(fund.transactionType()).isEqualTo(LedgerTransactionType.PROMOTION_FUND);
        assertThat(fund.referenceType()).isEqualTo(BillingReferenceType.PROMOTION_FUNDING);
        assertThat(fund.referenceId()).isEqualTo(11L);
        assertThat(fund.entries()).containsExactly(
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.AVAILABLE, 1_000L));

        assertThat(withdraw.operationKey()).isEqualTo("PROMOTION_WITHDRAW:12");
        assertThat(withdraw.transactionType()).isEqualTo(LedgerTransactionType.PROMOTION_WITHDRAW);
        assertThat(withdraw.referenceType()).isEqualTo(BillingReferenceType.PROMOTION_WITHDRAWAL);
        assertThat(withdraw.referenceId()).isEqualTo(12L);
        assertThat(withdraw.entries()).containsExactly(
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.AVAILABLE, -300L));
    }

    @Test
    @DisplayName("예약은 가용 − / 예약 +, 해제는 예약 − / 가용 + 두 줄이고 둘 다 예약(COUPON_BUDGET)을 참조한다")
    void reserveAndReleaseLines() {
        BudgetReservation reservation = reservation(7L, 42L, 600L);
        LedgerOperation reserve = PromotionLedgerOperations.reserve(reservation, NOW);
        LedgerOperation release = PromotionLedgerOperations.release(reservation, release(21L, 7L, 200L), NOW);

        assertThat(reserve.operationKey()).isEqualTo("BUDGET_RESERVE:7");
        assertThat(reserve.transactionType()).isEqualTo(LedgerTransactionType.BUDGET_RESERVE);
        assertThat(reserve.referenceType()).isEqualTo(BillingReferenceType.COUPON_BUDGET);
        assertThat(reserve.referenceId()).isEqualTo(7L);
        assertThat(reserve.entries()).containsExactly(
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.AVAILABLE, -600L),
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.RESERVED, 600L));

        assertThat(release.operationKey()).isEqualTo("BUDGET_RELEASE:21");
        assertThat(release.transactionType()).isEqualTo(LedgerTransactionType.BUDGET_RELEASE);
        assertThat(release.referenceType()).isEqualTo(BillingReferenceType.COUPON_BUDGET);
        assertThat(release.referenceId()).isEqualTo(7L);
        assertThat(release.entries()).containsExactly(
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.RESERVED, -200L),
                LedgerEntry.of(WALLET_ID, PointType.PROMOTION, BalanceBucket.AVAILABLE, 200L));
    }

    @Test
    @DisplayName("저장 전(ID 없는) 기록이나 다른 예약의 해제 기록으로는 원장을 만들지 않는다")
    void requiresSavedRecords() {
        BudgetReservation reservation = reservation(7L, 42L, 600L);

        assertThatThrownBy(() -> PromotionLedgerOperations.fund(
                PromotionFunding.of("PF-1", WALLET_ID, 1L, PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null, 9L),
                NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PromotionLedgerOperations.release(reservation, release(21L, 8L, 1L), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예약 합은 충전 합을 넘지 못한다: 가용 칸을 넘는 예약은 INSUFFICIENT_BALANCE로 거절되고 지갑은 그대로다")
    void reservationsCannotExceedFunding() {
        Wallet wallet = promotionWallet();
        apply(wallet, PromotionLedgerOperations.fund(funding(11L, 1_000L), NOW));
        apply(wallet, PromotionLedgerOperations.reserve(reservation(7L, 42L, 600L), NOW));
        apply(wallet, PromotionLedgerOperations.reserve(reservation(8L, 43L, 400L), NOW));

        assertThatThrownBy(() -> apply(wallet, PromotionLedgerOperations.reserve(reservation(9L, 44L, 1L), NOW)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThat(wallet.getPromotionBalanceHp()).isZero();
        assertThat(wallet.getReservedPromotionHp()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("회수는 예약되지 않은 가용 칸에서만 빠진다: 예약 칸이 남아 있어도 가용 칸을 넘는 회수는 거절된다")
    void withdrawOnlyFromUnreserved() {
        Wallet wallet = promotionWallet();
        apply(wallet, PromotionLedgerOperations.fund(funding(11L, 1_000L), NOW));
        apply(wallet, PromotionLedgerOperations.reserve(reservation(7L, 42L, 600L), NOW));

        assertThatThrownBy(() -> apply(wallet, PromotionLedgerOperations.withdraw(withdrawal(12L, 401L), NOW)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        apply(wallet, PromotionLedgerOperations.withdraw(withdrawal(13L, 400L), NOW));

        assertThat(wallet.getPromotionBalanceHp()).isZero();
        assertThat(wallet.getReservedPromotionHp()).isEqualTo(600L);
    }

    @Test
    @DisplayName("해제는 예약 칸에서 가용 칸으로 되돌리고, 예약 칸을 넘는 해제는 거절된다. 가용 칸 = 충전 − 회수 − 예약 + 해제")
    void releaseReturnsToAvailable() {
        Wallet wallet = promotionWallet();
        BudgetReservation reservation = reservation(7L, 42L, 600L);
        apply(wallet, PromotionLedgerOperations.fund(funding(11L, 1_000L), NOW));
        apply(wallet, PromotionLedgerOperations.reserve(reservation, NOW));
        apply(wallet, PromotionLedgerOperations.release(reservation, release(21L, 7L, 250L), NOW));
        apply(wallet, PromotionLedgerOperations.withdraw(withdrawal(12L, 100L), NOW));

        assertThatThrownBy(() -> apply(wallet,
                PromotionLedgerOperations.release(reservation, release(22L, 7L, 351L), NOW)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThat(wallet.getPromotionBalanceHp()).isEqualTo(1_000L - 100L - 600L + 250L);
        assertThat(wallet.getReservedPromotionHp()).isEqualTo(600L - 250L);
        assertThat(wallet.getHeldPromotionHp()).isZero();
    }

    // 원장 기록 UseCase와 같은 방식으로 작업의 줄을 차례로 지갑에 반영한다. 한 줄이라도 거절되면 예외가 난다(실제로는 트랜잭션이 롤백된다).
    // 거절되는 작업은 첫 줄(감소)에서 거절되도록 만들어, 이 테스트에서도 지갑이 바뀌지 않는다.
    private static void apply(Wallet wallet, LedgerOperation operation) {
        for (int line = 1; line <= operation.entries().size(); line++) {
            LedgerEntry entry = operation.entries().get(line - 1);
            wallet.applyLedger(PointLedger.builder().walletId(entry.walletId()).pointType(entry.pointType())
                    .balanceBucket(entry.balanceBucket()).amountHp(entry.amountHp())
                    .transactionType(operation.transactionType()).referenceType(operation.referenceType())
                    .referenceId(operation.referenceId()).operationKey(operation.operationKey())
                    .idempotencyKey(operation.idempotencyKey(line)).occurredAt(operation.occurredAt()).build());
        }
    }

    private static Wallet promotionWallet() {
        Wallet wallet = Wallet.createPromotion();
        ReflectionTestUtils.setField(wallet, "id", WALLET_ID);
        return wallet;
    }

    private static PromotionFunding funding(long id, long amountHp) {
        PromotionFunding funding = PromotionFunding.of("PF-" + id, WALLET_ID, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-" + id, "메모", 9L);
        ReflectionTestUtils.setField(funding, "id", id);
        return funding;
    }

    private static PromotionWithdrawal withdrawal(long id, long amountHp) {
        PromotionWithdrawal withdrawal = PromotionWithdrawal.of("PW-" + id, WALLET_ID, amountHp, "결재-" + id, null, 9L);
        ReflectionTestUtils.setField(withdrawal, "id", id);
        return withdrawal;
    }

    private static BudgetReservation reservation(long id, long couponId, long amountHp) {
        BudgetReservation reservation = BudgetReservation.reserve("coupon-" + couponId + "-reserve-v1", WALLET_ID,
                couponId, 1, amountHp);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    private static BudgetRelease release(long id, long reservationId, long amountHp) {
        BudgetRelease release = BudgetRelease.of("release-" + id, reservationId, amountHp, "ADMIN", null);
        ReflectionTestUtils.setField(release, "id", id);
        return release;
    }
}
