package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

// 지갑 캐시와 원장 합계의 대사 결과·불일치 탐지·일관된 읽기 시점을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class WalletReconciliationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("원장이 없는 신규 지갑은 네 버킷 모두 캐시 0·원장 합계 0으로 일치한다")
    void newWallet() {
        Long walletId = wallet(1L);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.walletId()).isEqualTo(walletId);
        assertThat(result.matched()).isTrue();
        assertThat(result.buckets()).hasSize(4).allSatisfy(bucket -> {
            assertThat(bucket.walletHp()).isZero();
            assertThat(bucket.ledgerHp()).isZero();
            assertThat(bucket.differenceHp()).isZero();
        });
    }

    @Test
    @DisplayName("원장 기록 후 네 버킷의 캐시 금액과 원장 합계가 일치한다")
    void matched() {
        Long walletId = walletWithAllBuckets();

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isTrue();
        assertBucket(result, PointType.CHARGE, BalanceBucket.AVAILABLE, 7_000L, 7_000L);
        assertBucket(result, PointType.CHARGE, BalanceBucket.HELD, 3_000L, 3_000L);
        assertBucket(result, PointType.REVENUE, BalanceBucket.AVAILABLE, 1_500L, 1_500L);
        assertBucket(result, PointType.REVENUE, BalanceBucket.HELD, 500L, 500L);
    }

    @ParameterizedTest(name = "{0}/{1} 캐시 불일치")
    @CsvSource({
            "CHARGE, AVAILABLE, charge_balance_hp",
            "REVENUE, AVAILABLE, revenue_balance_hp",
            "CHARGE, HELD, held_charge_hp",
            "REVENUE, HELD, held_revenue_hp"
    })
    @DisplayName("지갑 캐시가 원장 합계와 다른 버킷만 불일치로 탐지하고, 원장·지갑은 바꾸지 않는다")
    void cacheMismatch(PointType pointType, BalanceBucket balanceBucket, String column) {
        Long walletId = walletWithAllBuckets();
        jdbcTemplate.update("UPDATE billing.wallet SET " + column + " = " + column + " + 1 WHERE id = ?", walletId);
        Map<String, Object> walletBefore = walletRow(walletId);
        List<Map<String, Object>> ledgerBefore = ledgerRows(walletId);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        WalletReconciliation.BucketReconciliation mismatched = result.bucket(pointType, balanceBucket);
        assertThat(mismatched.differenceHp()).isEqualTo(1L);
        assertThat(mismatched.walletHp()).isEqualTo(mismatched.ledgerHp() + 1);
        assertThat(result.buckets()).filteredOn(bucket -> !bucket.matched()).containsExactly(mismatched);
        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(ledgerRows(walletId)).isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("지갑 변경 없이 원장만 있는 경우 원장 쪽이 큰 불일치(음수 차이)로 탐지하고 자동 보정하지 않는다")
    void ledgerOnlyMismatch() {
        Long walletId = wallet(1L);
        jdbcTemplate.update("""
                INSERT INTO billing.point_ledger (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                    reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                VALUES (?, 'REVENUE', 'HELD', 700, 'HOLD', 'EXCHANGE', 1, 'MANUAL', 'MANUAL:1', now(), now())""", walletId);

        WalletReconciliation result = walletReconciliationUseCase.reconcile(walletId);

        assertThat(result.matched()).isFalse();
        assertBucket(result, PointType.REVENUE, BalanceBucket.HELD, 0L, 700L);
        assertThat(result.bucket(PointType.REVENUE, BalanceBucket.HELD).differenceHp()).isEqualTo(-700L);
        assertThat(walletRepository.findById(walletId).orElseThrow().getHeldRevenueHp()).isZero();
    }

    @Test
    @DisplayName("거래가 계속 커밋되는 동안 대사해도 일관된 스냅숏으로 읽어 불일치로 잘못 탐지하지 않는다")
    void consistentSnapshotDuringConcurrentOperations() throws Exception {
        Long walletId = wallet(1L);
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> writer = executor.submit(() -> {
                int count = 0;
                while (running.get() && count < 300) {
                    count++;
                    ledgerRecordUseCase.record(charge(walletId, "CHARGE:" + count, 100L));
                    ledgerRecordUseCase.record(new LedgerOperation("HOLD:" + count, LedgerTransactionType.HOLD,
                            BillingReferenceType.LECTURE_ORDER, (long) count, null, NOW,
                            LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 40L)));
                }
                return count;
            });

            for (int i = 0; i < 200; i++) {
                assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("대사 %d회차", i).isTrue();
            }
            running.set(false);
            assertThat(writer.get(60, TimeUnit.SECONDS)).isPositive();
        } finally {
            running.set(false);
            executor.shutdownNow();
        }
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("없는 지갑을 대사하면 WALLET_NOT_FOUND로 거절한다")
    void walletNotFound() {
        assertThatThrownBy(() -> walletReconciliationUseCase.reconcile(999_999L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.WALLET_NOT_FOUND));
    }

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전·지급·보류로 네 버킷(충전 7,000/보류 충전 3,000/수익 1,500/보류 수익 500)에 잔액이 있는 지갑을 만든다.
    private Long walletWithAllBuckets() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 10_000L));
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:1", LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, 1L, null, NOW,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 2_000L))));
        ledgerRecordUseCase.record(new LedgerOperation("HOLD:1", LedgerTransactionType.HOLD,
                BillingReferenceType.LECTURE_ORDER, 1L, null, NOW,
                LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 3_000L)));
        ledgerRecordUseCase.record(new LedgerOperation("HOLD:2", LedgerTransactionType.HOLD,
                BillingReferenceType.LECTURE_ORDER, 2L, null, NOW,
                LedgerEntry.move(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 500L)));
        return walletId;
    }

    // 충전 HP 가용 잔액을 늘리는 충전 원장 기록 요청을 만든다.
    private LedgerOperation charge(Long walletId, String operationKey, long amountHp) {
        return new LedgerOperation(operationKey, LedgerTransactionType.CHARGE, BillingReferenceType.POINT_CHARGE, 1L,
                null, NOW, List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp)));
    }

    // 대사 결과에서 버킷의 캐시 금액·원장 합계를 확인한다.
    private void assertBucket(WalletReconciliation result, PointType pointType, BalanceBucket balanceBucket,
            long walletHp, long ledgerHp) {
        WalletReconciliation.BucketReconciliation bucket = result.bucket(pointType, balanceBucket);
        assertThat(bucket.walletHp()).as("%s/%s walletHp", pointType, balanceBucket).isEqualTo(walletHp);
        assertThat(bucket.ledgerHp()).as("%s/%s ledgerHp", pointType, balanceBucket).isEqualTo(ledgerHp);
    }

    // 지갑 행 전체를 읽는다.
    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    // 지갑의 원장 행 전체를 ID 순서로 읽는다.
    private List<Map<String, Object>> ledgerRows(Long walletId) {
        return jdbcTemplate.queryForList("SELECT * FROM billing.point_ledger WHERE wallet_id = ? ORDER BY id", walletId);
    }
}
