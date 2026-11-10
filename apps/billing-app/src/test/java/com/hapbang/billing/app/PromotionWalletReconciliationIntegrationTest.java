package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 프로모션 지갑 대사가 칸 캐시 대조에 더해 P1(가용 칸 식·earmark ≤ 순 충전)·P3(예약·보류 칸 = 예산 예약 합)·예약별 원장 합을 보고,
// 일부러 어긋나게 만든 예약 행·지갑 칸·원장을 불일치로 잡되 고치지 않는지 실제 PostgreSQL로 검증한다.
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PromotionWalletReconciliationIntegrationTest {

    private static final long ADMIN = 900L;
    private static final List<String> ALWAYS = List.of(PromotionWalletReconciliation.P1_AVAILABLE,
            PromotionWalletReconciliation.P1_AVAILABLE_NON_NEGATIVE, PromotionWalletReconciliation.P1_EARMARK,
            PromotionWalletReconciliation.P3_RESERVED, PromotionWalletReconciliation.P3_HELD);

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    ChangedWalletReconciliationUseCase changedWalletReconciliationUseCase;

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

    @Autowired
    PromotionWithdrawalUseCase promotionWithdrawalUseCase;

    @Autowired
    PromotionBudgetUseCase promotionBudgetUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("충전·회수·예약·해제 뒤 프로모션 지갑은 여덟 칸과 P1·P3 불변식이 모두 맞고 예약별 원장 불일치가 없다")
    void matched() {
        Long walletId = promotionWithActivity();

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isTrue();
        assertThat(result.buckets()).hasSize(8).allMatch(WalletReconciliation.BucketReconciliation::matched);
        assertThat(result.invariants()).extracting(WalletReconciliation.InvariantCheck::name)
                .containsExactlyElementsOf(ALWAYS);
        // 가용 = 충전 10,000 − 회수 1,000 − 예약(3,000 + 2,000) + 해제 500
        assertThat(result.invariant(PromotionWalletReconciliation.P1_AVAILABLE))
                .extracting(WalletReconciliation.InvariantCheck::leftHp, WalletReconciliation.InvariantCheck::rightHp)
                .containsExactly(4_500L, 4_500L);
        // earmark = 총액 − 해제됨 = 4,500 ≤ 순 충전 9,000
        assertThat(result.invariant(PromotionWalletReconciliation.P1_EARMARK))
                .extracting(WalletReconciliation.InvariantCheck::leftHp, WalletReconciliation.InvariantCheck::rightHp)
                .containsExactly(4_500L, 9_000L);
        assertThat(result.invariant(PromotionWalletReconciliation.P3_RESERVED).rightHp()).isEqualTo(4_500L);
        assertThat(result.invariant(PromotionWalletReconciliation.P3_HELD).rightHp()).isZero();
    }

    @Test
    @DisplayName("회원 지갑은 프로모션 불변식을 보지 않는다")
    void memberWalletHasNoInvariants() {
        Long walletId = walletRepository.save(Wallet.create(1L)).getId();

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isTrue();
        assertThat(result.invariants()).isEmpty();
    }

    @Test
    @DisplayName("예약 행의 남은 예산을 보류 중으로 옮기면(합은 그대로) 칸 대조는 맞아도 P3_RESERVED·P3_HELD 불일치로 잡는다")
    void reservationSplitMismatch() {
        Long walletId = promotionWithActivity();
        jdbcTemplate.update("""
                UPDATE billing.budget_reservation SET remaining_hp = remaining_hp - 100, held_hp = held_hp + 100
                WHERE coupon_id = 42""");

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        assertThat(result.buckets()).allMatch(WalletReconciliation.BucketReconciliation::matched);
        assertThat(mismatchedNames(result)).containsExactly(PromotionWalletReconciliation.P3_RESERVED,
                PromotionWalletReconciliation.P3_HELD);
        assertThat(result.invariant(PromotionWalletReconciliation.P3_HELD))
                .extracting(WalletReconciliation.InvariantCheck::leftHp, WalletReconciliation.InvariantCheck::rightHp)
                .containsExactly(0L, 100L);
    }

    @Test
    @DisplayName("예약 행의 해제됨이 원장·해제 기록과 다르면 그 예약의 P2 원장·기록 불일치와 P3_RESERVED 불일치로 잡는다")
    void releasedMismatch() {
        Long walletId = promotionWithActivity();
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.budget_reservation WHERE coupon_id = 42", Long.class);
        jdbcTemplate.update("""
                UPDATE billing.budget_reservation SET remaining_hp = remaining_hp - 1, released_hp = released_hp + 1
                WHERE id = ?""", reservationId);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        assertThat(mismatchedNames(result)).containsExactly(PromotionWalletReconciliation.P3_RESERVED,
                PromotionWalletReconciliation.P2_RELEASE_LEDGER + " reservationId=" + reservationId,
                PromotionWalletReconciliation.P2_RELEASE_RECORDS + " reservationId=" + reservationId);
    }

    @Test
    @DisplayName("예약 총액을 원장보다 크게 바꾸면 P2 예약 원장·P3_RESERVED 불일치, 순 충전을 넘으면 P1_EARMARK 불일치로 잡는다")
    void earmarkExceedsFunding() {
        Long walletId = promotionWithActivity();
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.budget_reservation WHERE coupon_id = 43", Long.class);
        jdbcTemplate.update("""
                UPDATE billing.budget_reservation SET amount_hp = amount_hp + 10000, remaining_hp = remaining_hp + 10000
                WHERE id = ?""", reservationId);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        assertThat(mismatchedNames(result)).containsExactly(PromotionWalletReconciliation.P1_EARMARK,
                PromotionWalletReconciliation.P3_RESERVED,
                PromotionWalletReconciliation.P2_RESERVE_LEDGER + " reservationId=" + reservationId);
        assertThat(result.invariant(PromotionWalletReconciliation.P1_EARMARK))
                .extracting(WalletReconciliation.InvariantCheck::leftHp, WalletReconciliation.InvariantCheck::rightHp)
                .containsExactly(14_500L, 9_000L);
    }

    @Test
    @DisplayName("가용 칸 캐시가 원장과 다르면 칸 불일치와 P1_AVAILABLE 불일치로 잡고, 지갑·원장·예약을 고치지 않는다")
    void availableCacheMismatch() {
        Long walletId = promotionWithActivity();
        jdbcTemplate.update("UPDATE billing.wallet SET promotion_balance_hp = promotion_balance_hp + 7 WHERE id = ?",
                walletId);
        List<Map<String, Object>> reservationsBefore = reservationRows();
        long ledgerBefore = ledgerCount(walletId);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        assertThat(result.buckets()).filteredOn(bucket -> !bucket.matched()).singleElement()
                .satisfies(bucket -> {
                    assertThat(bucket.pointType()).isEqualTo(PointType.PROMOTION);
                    assertThat(bucket.balanceBucket()).isEqualTo(BalanceBucket.AVAILABLE);
                    assertThat(bucket.differenceHp()).isEqualTo(7L);
                });
        assertThat(mismatchedNames(result)).containsExactly(PromotionWalletReconciliation.P1_AVAILABLE);
        assertThat(walletRepository.findById(walletId).orElseThrow().getPromotionBalanceHp()).isEqualTo(4_507L);
        assertThat(reservationRows()).isEqualTo(reservationsBefore);
        assertThat(ledgerCount(walletId)).isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("예약 원장 없이 예약 행만 있으면 그 예약의 P2 예약 원장 불일치와 P3_RESERVED 불일치로 잡는다")
    void reservationWithoutLedger() {
        Long walletId = promotionWithActivity();
        jdbcTemplate.update("""
                INSERT INTO billing.budget_reservation (work_id, wallet_id, coupon_id, policy_version, amount_hp,
                    remaining_hp, held_hp, paid_hp, released_hp, created_at, updated_at)
                VALUES ('coupon-99-reserve-manual', ?, 99, 1, 100, 100, 0, 0, 0, now(), now())""", walletId);
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.budget_reservation WHERE coupon_id = 99", Long.class);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(mismatchedNames(result)).containsExactly(PromotionWalletReconciliation.P3_RESERVED,
                PromotionWalletReconciliation.P2_RESERVE_LEDGER + " reservationId=" + reservationId);
    }

    @Test
    @DisplayName("대사 배치는 프로모션 불변식 불일치를 불일치 지갑으로 세고 기존 error 표식에 불변식을 함께 남긴다")
    void batchReportsInvariantMismatch(CapturedOutput output) {
        Long walletId = promotionWithActivity();
        jdbcTemplate.update("""
                UPDATE billing.budget_reservation SET remaining_hp = remaining_hp - 100, held_hp = held_hp + 100
                WHERE coupon_id = 42""");

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(
                Instant.now());

        assertThat(result.mismatchedWalletIds()).containsExactly(walletId);
        assertThat(output.getOut()).contains("잔액 대사 불일치 walletId=" + walletId + ", buckets=[], invariants=["
                + PromotionWalletReconciliation.P3_RESERVED + " 4500 = 4400, " + PromotionWalletReconciliation.P3_HELD
                + " 0 = 100]");
    }

    // 충전 10,000 → 회수 1,000 → 쿠폰 42에 3,000·43에 2,000 예약 → 쿠폰 42에서 500 해제. 가용 4,500, 예약 4,500
    private Long promotionWithActivity() {
        promotionFundingUseCase.fund(ADMIN, new PromotionFundingCommand("PF-1", 10_000L,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null));
        promotionWithdrawalUseCase.withdraw(ADMIN, new PromotionWithdrawalCommand("PW-1", 1_000L, "결재-2", null));
        BudgetReserveResult reserved = promotionBudgetUseCase.reserve(
                new BudgetReserveCommand("coupon-42-reserve-v1", 42L, 1, 3_000L));
        promotionBudgetUseCase.reserve(new BudgetReserveCommand("coupon-43-reserve-v1", 43L, 1, 2_000L));
        promotionBudgetUseCase.release(new BudgetReleaseCommand("coupon-42-release-v1", reserved.reservationId(),
                500L, "UNISSUED:5", null));
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow().getId();
    }

    private static List<String> mismatchedNames(WalletReconciliation result) {
        return result.invariants().stream().filter(check -> !check.matched())
                .map(WalletReconciliation.InvariantCheck::name).toList();
    }

    private List<Map<String, Object>> reservationRows() {
        return jdbcTemplate.queryForList("SELECT * FROM billing.budget_reservation ORDER BY id");
    }

    private long ledgerCount(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?", Long.class,
                walletId);
    }
}
