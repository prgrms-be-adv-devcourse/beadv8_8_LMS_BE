package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFunding;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.PromotionWithdrawal;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.BudgetReleaseRepository;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.PromotionFundingRepository;
import com.hapbang.billing.out.PromotionWithdrawalRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 프로모션 지갑의 충전·회수·예산 예약·해제 원장이 기존 원장 기록(LedgerRecordUseCase)으로 지갑 칸을 바꾸고,
// 칸이 음수가 되는 작업은 아무것도 남기지 않으며, 지갑 대사가 프로모션 칸도 맞추는지 실제 PostgreSQL로 검증한다.
@IntegrationTest
class PromotionLedgerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final long ADMIN = 9L;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PromotionFundingRepository promotionFundingRepository;

    @Autowired
    PromotionWithdrawalRepository promotionWithdrawalRepository;

    @Autowired
    BudgetReservationRepository budgetReservationRepository;

    @Autowired
    BudgetReleaseRepository budgetReleaseRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("프로모션 지갑은 Flyway가 만든 한 행뿐이고 회원이 없으며 모든 칸이 0이다")
    void promotionWalletRow() {
        Wallet promotion = promotionWallet();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE wallet_type = 'PROMOTION'",
                Integer.class)).isOne();
        assertThat(promotion.isPromotion()).isTrue();
        assertThat(promotion.getUserId()).isNull();
        for (PointType pointType : PointType.values()) {
            for (BalanceBucket balanceBucket : BalanceBucket.values()) {
                assertThat(promotion.balanceOf(pointType, balanceBucket)).isZero();
            }
        }
    }

    @Test
    @DisplayName("충전·예약·해제·회수 원장으로 프로모션 칸이 바뀌고 대사가 일치하며, 예약의 원장 합이 총액·해제됨과 같다")
    void fundReserveReleaseWithdraw() {
        Long walletId = promotionWallet().getId();
        PromotionFunding funding = promotionFundingRepository.saveAndFlush(PromotionFunding.of("PF-20261110-01",
                walletId, 1_000_000L, PromotionFundingSourceType.ADMIN_GRANT, "결재-2026-118", "11월 프로모션", ADMIN));
        ledgerRecordUseCase.record(PromotionLedgerOperations.fund(funding, NOW));

        BudgetReservation reservation = budgetReservationRepository.saveAndFlush(
                BudgetReservation.reserve("coupon-42-reserve-v1", walletId, 42L, 1, 100_000L));
        ledgerRecordUseCase.record(PromotionLedgerOperations.reserve(reservation, NOW));

        BudgetRelease release = budgetReleaseRepository.saveAndFlush(BudgetRelease.of(
                "coupon-42-release-unissued-v1", reservation.getId(), 30_000L, "UNISSUED:30", "발급 종료"));
        reservation.release(release.getAmountHp());
        reservation = budgetReservationRepository.saveAndFlush(reservation);
        ledgerRecordUseCase.record(PromotionLedgerOperations.release(reservation, release, NOW));

        PromotionWithdrawal withdrawal = promotionWithdrawalRepository.saveAndFlush(
                PromotionWithdrawal.of("PW-20261110-01", walletId, 200_000L, "결재-2026-119", null, ADMIN));
        ledgerRecordUseCase.record(PromotionLedgerOperations.withdraw(withdrawal, NOW));

        Wallet promotion = walletRepository.findById(walletId).orElseThrow();
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(1_000_000L - 100_000L + 30_000L - 200_000L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(70_000L);
        assertThat(promotion.getHeldPromotionHp()).isZero();

        BudgetReservation saved = budgetReservationRepository.findByCouponId(42L).orElseThrow();
        assertThat(saved.getRemainingHp()).isEqualTo(70_000L);
        assertThat(saved.getReleasedHp()).isEqualTo(30_000L);
        assertThat(saved.isBalanced()).isTrue();
        // 예약 칸 = 남은 예산 합, 예약 원장(예약 칸 +) = 총액, 해제 원장(가용 칸 +) 합 = 해제됨
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(saved.getRemainingHp());
        assertThat(ledgerSum(saved.getId(), "BUDGET_RESERVE", "RESERVED")).isEqualTo(saved.getAmountHp());
        assertThat(ledgerSum(saved.getId(), "BUDGET_RELEASE", "AVAILABLE")).isEqualTo(saved.getReleasedHp());

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);
        assertThat(result.matched()).isTrue();
        assertThat(result.buckets()).hasSize(8);
        assertThat(result.bucket(PointType.PROMOTION, BalanceBucket.AVAILABLE).ledgerHp()).isEqualTo(730_000L);
        assertThat(result.bucket(PointType.PROMOTION, BalanceBucket.RESERVED).ledgerHp()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("같은 충전 원장을 다시 기록하면 처음 기록을 돌려주고 잔액을 다시 바꾸지 않는다")
    void replay() {
        Long walletId = promotionWallet().getId();
        PromotionFunding funding = promotionFundingRepository.saveAndFlush(PromotionFunding.of("PF-1", walletId,
                5_000L, PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null, ADMIN));

        LedgerOperationResult first = ledgerRecordUseCase.record(PromotionLedgerOperations.fund(funding, NOW));
        LedgerOperationResult second = ledgerRecordUseCase.record(PromotionLedgerOperations.fund(funding, NOW));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.ledgerIds()).isEqualTo(first.ledgerIds());
        assertThat(walletRepository.findById(walletId).orElseThrow().getPromotionBalanceHp()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("가용 칸을 넘는 예약·회수는 INSUFFICIENT_BALANCE로 거절되고 지갑·원장은 그대로다")
    void insufficientBalanceChangesNothing() {
        Long walletId = promotionWallet().getId();
        PromotionFunding funding = promotionFundingRepository.saveAndFlush(PromotionFunding.of("PF-1", walletId,
                1_000L, PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null, ADMIN));
        ledgerRecordUseCase.record(PromotionLedgerOperations.fund(funding, NOW));
        BudgetReservation reservation = budgetReservationRepository.saveAndFlush(
                BudgetReservation.reserve("coupon-43-reserve-v1", walletId, 43L, 1, 1_001L));
        PromotionWithdrawal withdrawal = promotionWithdrawalRepository.saveAndFlush(
                PromotionWithdrawal.of("PW-1", walletId, 1_001L, "결재-2", null, ADMIN));
        Map<String, Object> walletBefore = walletRow(walletId);
        List<Map<String, Object>> ledgerBefore = ledgerRows(walletId);

        assertThatThrownBy(() -> ledgerRecordUseCase.record(PromotionLedgerOperations.reserve(reservation, NOW)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        assertThatThrownBy(() -> ledgerRecordUseCase.record(PromotionLedgerOperations.withdraw(withdrawal, NOW)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));

        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(ledgerRows(walletId)).isEqualTo(ledgerBefore);
        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);
        assertThat(result.buckets()).allMatch(WalletReconciliation.BucketReconciliation::matched);
        // 이 테스트가 원장 기록 전에 직접 저장한 예약 행은 원장 없이 남으므로 프로모션 불변식(P3·P2)이 불일치로 잡는다
        assertThat(result.invariant(PromotionWalletReconciliation.P3_RESERVED).matched()).isFalse();
    }

    private Wallet promotionWallet() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow();
    }

    private long ledgerSum(Long reservationId, String transactionType, String balanceBucket) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger
                WHERE reference_type = 'COUPON_BUDGET' AND reference_id = ? AND transaction_type = ?
                  AND point_type = 'PROMOTION' AND balance_bucket = ?""", Long.class,
                reservationId, transactionType, balanceBucket);
    }

    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    private List<Map<String, Object>> ledgerRows(Long walletId) {
        return jdbcTemplate.queryForList("SELECT * FROM billing.point_ledger WHERE wallet_id = ? ORDER BY id", walletId);
    }
}
