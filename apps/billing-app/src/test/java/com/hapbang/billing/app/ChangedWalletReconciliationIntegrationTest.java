package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

// 변경된 지갑의 잔액 대사(#607, BD-139)가 시간 창(기본 72시간) 안에서 바뀐 지갑만 고르고, 불일치를 고치지 않는지 실제 PostgreSQL로 확인한다.
// 대사하지 않은 지갑은 일부러 캐시를 틀어 두고 불일치 목록에 없는 것으로 확인한다.
@IntegrationTest
class ChangedWalletReconciliationIntegrationTest {

    private static final Duration WINDOW = Duration.ofHours(72);

    @Autowired
    ChangedWalletReconciliationUseCase changedWalletReconciliationUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("창 안에서 수정된 지갑은 대사하고, 창보다 오래 바뀌지 않은 지갑은 대사하지 않는다")
    void onlyWalletsUpdatedInWindow() {
        Instant now = Instant.now();
        Long inside = mismatchedWallet(1L, now.minus(WINDOW).plus(1, ChronoUnit.HOURS));
        Long outside = mismatchedWallet(2L, now.minus(WINDOW).minus(1, ChronoUnit.HOURS));

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        assertThat(result.since()).isEqualTo(now.minus(WINDOW));
        assertThat(result.mismatchedWalletIds()).contains(inside).doesNotContain(outside);
        assertThat(result.failedWalletIds()).isEmpty();
    }

    @Test
    @DisplayName("지갑은 오래전에 수정됐어도 창 안에 원장이 기록됐으면 대사해, 캐시에 반영되지 않은 원장을 불일치로 잡는다")
    void walletWithRecentLedgerOnly() {
        Instant now = Instant.now();
        Long walletId = walletRepository.save(Wallet.create(1L)).getId();
        backdateWallet(walletId, now.minus(Duration.ofDays(10)));
        jdbcTemplate.update("""
                INSERT INTO billing.point_ledger (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                    reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                VALUES (?, 'CHARGE', 'AVAILABLE', 500, 'CHARGE', 'POINT_CHARGE', 1, 'MANUAL', 'MANUAL:1', ?, ?)""",
                walletId, Timestamp.from(now.minus(Duration.ofHours(1))), Timestamp.from(now.minus(Duration.ofHours(1))));

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        assertThat(result.mismatchedWalletIds()).contains(walletId);
    }

    @Test
    @DisplayName("원장 없이 캐시만 바뀐 지갑을 불일치로 잡고, 지갑·원장을 고치지 않는다")
    void cacheOnlyChangeIsReportedNotFixed() {
        Instant now = Instant.now();
        Long walletId = walletRepository.save(Wallet.create(1L)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:1", LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, 1L, null, now,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L))));
        jdbcTemplate.update("UPDATE billing.wallet SET charge_balance_hp = charge_balance_hp + 1, updated_at = now() "
                + "WHERE id = ?", walletId);
        Map<String, Object> walletBefore = walletRow(walletId);
        List<Map<String, Object>> ledgerBefore = ledgerRows(walletId);

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        assertThat(result.mismatchedWalletIds()).contains(walletId);
        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(ledgerRows(walletId)).isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("원장대로 움직인 지갑은 일치로 세고, 원장과 수정이 함께 있어도 한 번만 대사한다")
    void matchedWalletCountedOnce() {
        Instant now = Instant.now();
        Long walletId = walletRepository.save(Wallet.create(1L)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:1", LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, 1L, null, now,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L))));
        int walletsInWindow = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.wallet WHERE updated_at >= ?", Integer.class,
                Timestamp.from(now.minus(WINDOW)));

        ChangedWalletReconciliationUseCase.Result result = changedWalletReconciliationUseCase.reconcileChanged(now);

        assertThat(result.mismatchedWalletIds()).doesNotContain(walletId);
        assertThat(result.total()).isEqualTo(walletsInWindow);
    }

    // 캐시를 원장보다 1 크게 틀고, 수정·생성 시각을 지정한 시각으로 되돌린 지갑을 만든다.
    private Long mismatchedWallet(Long userId, Instant changedAt) {
        Long walletId = walletRepository.save(Wallet.create(userId)).getId();
        jdbcTemplate.update("UPDATE billing.wallet SET charge_balance_hp = charge_balance_hp + 1 WHERE id = ?", walletId);
        backdateWallet(walletId, changedAt);
        return walletId;
    }

    private void backdateWallet(Long walletId, Instant at) {
        jdbcTemplate.update("UPDATE billing.wallet SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(at), Timestamp.from(at), walletId);
    }

    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    private List<Map<String, Object>> ledgerRows(Long walletId) {
        return jdbcTemplate.queryForList("SELECT * FROM billing.point_ledger WHERE wallet_id = ? ORDER BY id", walletId);
    }
}
