package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointLedger;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointLedgerRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.testsupport.IntegrationTest;

// 원장 기록과 지갑 잔액 변경의 트랜잭션·멱등성·동시성을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class LedgerRecordIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final int THREADS = 8;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PointLedgerRepository pointLedgerRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ---------------------------------------------------------------------------------------------
    // 원장·잔액
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전 HP 가용 증가는 원장과 지갑 잔액이 함께 커밋된다")
    void chargeAvailable() {
        Long walletId = wallet(1L);

        LedgerOperationResult result = ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 10_000L));

        assertThat(result.replayed()).isFalse();
        assertThat(result.ledgerIds()).hasSize(1);
        assertBalances(walletId, 10_000L, 0L, 0L, 0L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT point_type, balance_bucket, amount_hp, transaction_type, idempotency_key
                FROM billing.point_ledger WHERE id = ?""", result.ledgerIds().getFirst()))
                .containsEntry("point_type", "CHARGE")
                .containsEntry("balance_bucket", "AVAILABLE")
                .containsEntry("amount_hp", 10_000L)
                .containsEntry("transaction_type", "CHARGE")
                .containsEntry("idempotency_key", "CHARGE:1:1");
        assertMatched(walletId);
    }

    @Test
    @DisplayName("수익 HP 가용 증가는 충전 HP와 분리되어 반영된다")
    void revenueAvailable() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));

        ledgerRecordUseCase.record(revenue(walletId, "PAYOUT:1", 3_000L));

        assertBalances(walletId, 1_000L, 3_000L, 0L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("가용 → 보류 이동과 보류 → 가용 이동은 같은 작업 키의 두 줄로 기록되고 HP 총량은 변하지 않는다")
    void moveBetweenBuckets() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 10_000L));
        ledgerRecordUseCase.record(revenue(walletId, "PAYOUT:1", 2_000L));

        LedgerOperationResult hold = ledgerRecordUseCase.record(move(walletId, "HOLD:1001", LedgerTransactionType.HOLD,
                PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 10_000L));
        assertBalances(walletId, 0L, 2_000L, 10_000L, 0L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM billing.point_ledger WHERE operation_key = 'HOLD:1001' ORDER BY id", String.class))
                .containsExactly("HOLD:1001:1", "HOLD:1001:2");
        assertThat(hold.ledgerIds()).hasSize(2);

        ledgerRecordUseCase.record(move(walletId, "RELEASE:1001", LedgerTransactionType.RELEASE,
                PointType.CHARGE, BalanceBucket.HELD, BalanceBucket.AVAILABLE, 4_000L));
        ledgerRecordUseCase.record(move(walletId, "HOLD:1002", LedgerTransactionType.HOLD,
                PointType.REVENUE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 500L));

        assertBalances(walletId, 4_000L, 1_500L, 6_000L, 500L);
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp() + wallet.getHeldChargeHp()).isEqualTo(10_000L);
        assertThat(wallet.getRevenueBalanceHp() + wallet.getHeldRevenueHp()).isEqualTo(2_000L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("잔액보다 큰 감소는 INSUFFICIENT_BALANCE로 거절하고 원장·잔액을 남기지 않는다")
    void insufficientBalance() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));

        assertBillingError(() -> ledgerRecordUseCase.record(move(walletId, "HOLD:1", LedgerTransactionType.HOLD,
                PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 1_001L)), BillingErrorCode.INSUFFICIENT_BALANCE);

        assertBalances(walletId, 1_000L, 0L, 0L, 0L);
        assertThat(ledgerCount("HOLD:1")).isZero();
    }

    @Test
    @DisplayName("Long 범위를 넘는 증가는 BALANCE_OVERFLOW로 거절하고 원장을 남기지 않는다")
    void balanceOverflow() {
        Long walletId = wallet(1L);
        jdbcTemplate.update("UPDATE billing.wallet SET charge_balance_hp = ? WHERE id = ?", Long.MAX_VALUE - 10, walletId);

        assertBillingError(() -> ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 11L)),
                BillingErrorCode.BALANCE_OVERFLOW);

        assertThat(walletRepository.findById(walletId).orElseThrow().getChargeBalanceHp()).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(ledgerCount("CHARGE:1")).isZero();
    }

    @Test
    @DisplayName("금융 거래 대상 지갑이 없으면 지갑을 만들지 않고 WALLET_NOT_FOUND로 거절한다")
    void walletNotFound() {
        assertBillingError(() -> ledgerRecordUseCase.record(charge(999_999L, "CHARGE:1", 100L)),
                BillingErrorCode.WALLET_NOT_FOUND);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet", Integer.class)).isZero();
        assertThat(ledgerCount("CHARGE:1")).isZero();
    }

    @Test
    @DisplayName("역분개는 원본 원장을 참조하는 새 원장으로 기록되고 원본은 바뀌지 않는다")
    void reversal() {
        Long walletId = wallet(1L);
        Long originalId = ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L)).ledgerIds().getFirst();

        Long reversalId = ledgerRecordUseCase.record(new LedgerOperation("REVERSAL:CHARGE:1", LedgerTransactionType.REVERSAL,
                BillingReferenceType.POINT_CHARGE, 1L, "충전 취소 정정", NOW,
                List.of(new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1_000L, originalId))))
                .ledgerIds().getFirst();

        assertBalances(walletId, 0L, 0L, 0L, 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT original_ledger_id FROM billing.point_ledger WHERE id = ?",
                Long.class, reversalId)).isEqualTo(originalId);
        assertThat(jdbcTemplate.queryForObject("SELECT amount_hp FROM billing.point_ledger WHERE id = ?",
                Long.class, originalId)).isEqualTo(1_000L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("역분개 원본 원장이 없으면 ORIGINAL_LEDGER_NOT_FOUND로 거절한다")
    void reversal_originalNotFound() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));

        assertBillingError(() -> ledgerRecordUseCase.record(new LedgerOperation("REVERSAL:X", LedgerTransactionType.REVERSAL,
                        BillingReferenceType.POINT_CHARGE, 1L, null, NOW,
                        List.of(new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1_000L, 999_999L)))),
                BillingErrorCode.ORIGINAL_LEDGER_NOT_FOUND);
        assertBalances(walletId, 1_000L, 0L, 0L, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 트랜잭션
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("원장 저장이 실패하면 앞서 반영한 지갑 변경과 원장 줄이 모두 롤백된다")
    void ledgerFailure_rollsBackWallet() {
        Long walletId = wallet(1L);
        // 다른 작업이 이미 'OP:2' 멱등키를 쓰고 있어 두 번째 줄 INSERT가 UNIQUE 충돌로 실패한다.
        insertLedgerRow(walletId, "OTHER", "OP:2", 1L);

        assertBillingError(() -> ledgerRecordUseCase.record(operation("OP", LedgerTransactionType.CHARGE,
                        LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L),
                        LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 500L))),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertBalances(walletId, 0L, 0L, 0L, 0L);
        assertThat(ledgerCount("OP")).isZero();
    }

    @Test
    @DisplayName("지갑 변경이 실패하면 앞서 저장한 원장 줄과 다른 지갑의 변경이 모두 롤백된다")
    void walletFailure_rollsBackLedger() {
        Long first = wallet(1L);
        Long second = wallet(2L);

        assertBillingError(() -> ledgerRecordUseCase.record(operation("OP", LedgerTransactionType.PAYOUT,
                        LedgerEntry.of(first, PointType.REVENUE, BalanceBucket.AVAILABLE, 1_000L),
                        LedgerEntry.of(second, PointType.CHARGE, BalanceBucket.HELD, -1_000L))),
                BillingErrorCode.INSUFFICIENT_BALANCE);

        assertBalances(first, 0L, 0L, 0L, 0L);
        assertBalances(second, 0L, 0L, 0L, 0L);
        assertThat(ledgerCount("OP")).isZero();
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션에 참여하며, 그 트랜잭션이 롤백되면 원장과 지갑 변경도 롤백된다")
    void joinsCallerTransaction() {
        Long walletId = wallet(1L);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
            throw new IllegalStateException("이후 업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertBalances(walletId, 0L, 0L, 0L, 0L);
        assertThat(ledgerCount("CHARGE:1")).isZero();
    }

    @Test
    @DisplayName("여러 지갑 사이 이동(보류 HP → 수익 HP)이 한 트랜잭션에서 함께 반영된다")
    void multiWalletOperation() {
        Long host = wallet(1L);
        Long guest = wallet(2L);
        ledgerRecordUseCase.record(charge(host, "CHARGE:1", 30_000L));
        ledgerRecordUseCase.record(move(host, "HOLD:7", LedgerTransactionType.HOLD,
                PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 30_000L));

        ledgerRecordUseCase.record(operation("PAYOUT:7", LedgerTransactionType.PAYOUT,
                LedgerEntry.of(guest, PointType.REVENUE, BalanceBucket.AVAILABLE, 20_000L),
                LedgerEntry.of(host, PointType.CHARGE, BalanceBucket.HELD, -20_000L)));

        assertBalances(host, 0L, 0L, 10_000L, 0L);
        assertBalances(guest, 0L, 20_000L, 0L, 0L);
        assertMatched(host);
        assertMatched(guest);
    }

    @Test
    @DisplayName("같은 지갑에 서로 다른 거래가 동시에 들어와도 모두 한 번씩 반영된다")
    void concurrentDistinctOperations() throws Exception {
        Long walletId = wallet(1L);
        List<Callable<LedgerOperationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS * 2; i++) {
            String key = "CHARGE:" + i;
            tasks.add(() -> ledgerRecordUseCase.record(charge(walletId, key, 100L)));
        }

        runTogether(tasks);

        assertBalances(walletId, 1_600L, 0L, 0L, 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?",
                Integer.class, walletId)).isEqualTo(16);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("잔액을 넘는 감소가 동시에 들어오면 잔액만큼만 성공하고 잔액은 음수가 되지 않는다")
    void concurrentDebits() throws Exception {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 500L));
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String key = "HOLD:" + i;
            tasks.add(() -> {
                try {
                    ledgerRecordUseCase.record(move(walletId, key, LedgerTransactionType.HOLD,
                            PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 100L));
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(5);
        assertBalances(walletId, 0L, 0L, 500L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("두 지갑을 반대 순서로 나열한 거래가 동시에 실행되어도 ID 오름차순 락으로 교착 없이 모두 처리된다")
    void opposingWalletOrder_noDeadlock() throws Exception {
        Long first = wallet(1L);
        Long second = wallet(2L);
        ledgerRecordUseCase.record(charge(first, "CHARGE:A", 10_000L));
        ledgerRecordUseCase.record(charge(second, "CHARGE:B", 10_000L));
        List<Callable<LedgerOperationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS * 2; i++) {
            String key = "TRANSFER:" + i;
            LedgerEntry fromFirst = LedgerEntry.of(first, PointType.CHARGE, BalanceBucket.AVAILABLE, -10L);
            LedgerEntry fromSecond = LedgerEntry.of(second, PointType.CHARGE, BalanceBucket.AVAILABLE, -10L);
            LedgerEntry toFirst = LedgerEntry.of(first, PointType.REVENUE, BalanceBucket.AVAILABLE, 10L);
            LedgerEntry toSecond = LedgerEntry.of(second, PointType.REVENUE, BalanceBucket.AVAILABLE, 10L);
            LedgerOperation operation = i % 2 == 0
                    ? operation(key, LedgerTransactionType.PAYOUT, fromSecond, toFirst)
                    : operation(key, LedgerTransactionType.PAYOUT, fromFirst, toSecond);
            tasks.add(() -> ledgerRecordUseCase.record(operation));
        }

        runTogether(tasks);

        assertBalances(first, 10_000L - 80L, 80L, 0L, 0L);
        assertBalances(second, 10_000L - 80L, 80L, 0L, 0L);
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션이 먼저 읽은 지갑을 다른 트랜잭션이 바꾼 뒤 기록해도 최신 잔액 기준으로 계산해 변경을 잃지 않는다")
    void staleWalletInCallerTransaction_noLostUpdate() throws Exception {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 다른 트랜잭션: 바깥 트랜잭션이 지갑을 읽은 뒤 +500을 커밋한다.
            Future<?> other = executor.submit(() -> {
                await(loaded);
                ledgerRecordUseCase.record(charge(walletId, "CHARGE:2", 500L));
                committed.countDown();
                return null;
            });

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Wallet stale = walletRepository.findById(walletId).orElseThrow();
                assertThat(stale.getChargeBalanceHp()).isEqualTo(1_000L);
                loaded.countDown();
                await(committed);
                // 영속성 컨텍스트에는 아직 1,000인 지갑이 있다. 락 이후 최신 잔액(1,500)에 +100을 더해야 한다.
                ledgerRecordUseCase.record(charge(walletId, "CHARGE:3", 100L));
                assertThat(stale.getChargeBalanceHp()).isEqualTo(1_600L);
            });
            other.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertBalances(walletId, 1_600L, 0L, 0L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("한 트랜잭션에서 같은 지갑을 여러 번 기록해도 앞선 미반영 변경이 다시 읽기로 지워지지 않는다")
    void multipleRecordsInSameTransaction_keepPendingChanges() {
        Long walletId = wallet(1L);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
            ledgerRecordUseCase.record(move(walletId, "HOLD:1", LedgerTransactionType.HOLD,
                    PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 300L));
            ledgerRecordUseCase.record(charge(walletId, "CHARGE:2", 200L));
        });

        assertBalances(walletId, 900L, 0L, 300L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("lockWallet은 호출한 쪽 트랜잭션이 없으면 거절한다")
    void lockWallet_requiresTransaction() {
        Long walletId = wallet(1L);

        assertThatThrownBy(() -> ledgerRecordUseCase.lockWallet(walletId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("lockWallet은 먼저 읽은 지갑을 락 이후 최신 잔액으로 다시 읽고, 없는 지갑은 WALLET_NOT_FOUND로 거절한다")
    void lockWallet_refreshesStaleWallet() throws Exception {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> other = executor.submit(() -> {
                await(loaded);
                ledgerRecordUseCase.record(charge(walletId, "CHARGE:2", 500L));
                committed.countDown();
                return null;
            });

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Wallet stale = walletRepository.findById(walletId).orElseThrow();
                loaded.countDown();
                await(committed);
                assertThat(stale.getChargeBalanceHp()).isEqualTo(1_000L);

                Wallet locked = ledgerRecordUseCase.lockWallet(walletId);

                assertThat(locked).isSameAs(stale);
                assertThat(locked.getChargeBalanceHp()).isEqualTo(1_500L);
            });
            other.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertBillingError(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> ledgerRecordUseCase.lockWallet(999_999L)), BillingErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("lockWallet의 지갑 락은 트랜잭션이 끝날 때까지 유지되어 다른 트랜잭션의 record()가 커밋 후에 최신 잔액으로 반영된다")
    void lockWallet_holdsLockUntilCommit() throws Exception {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> other;
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            other = transaction.execute(status -> {
                ledgerRecordUseCase.lockWallet(walletId);
                Future<?> waiting = executor.submit(() -> {
                    await(locked);
                    return ledgerRecordUseCase.record(charge(walletId, "CHARGE:2", 500L));
                });
                locked.countDown();
                waitUntilLockWaiting();
                // 락을 쥔 동안에는 다른 트랜잭션의 기록이 끝나지 않는다.
                assertThat(waiting.isDone()).isFalse();
                ledgerRecordUseCase.record(charge(walletId, "CHARGE:3", 100L));
                return waiting;
            });
            other.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertBalances(walletId, 1_600L, 0L, 0L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("저장소에서 읽은 원장 항목은 지갑에 다시 반영할 수 없다")
    void loadedLedgerCannotBeReapplied() {
        Long walletId = wallet(1L);
        Long ledgerId = ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L)).ledgerIds().getFirst();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Wallet wallet = walletRepository.findById(walletId).orElseThrow();
            PointLedger loaded = pointLedgerRepository.findById(ledgerId).orElseThrow();
            wallet.applyLedger(loaded);
        })).isInstanceOf(IllegalStateException.class);

        assertBalances(walletId, 1_000L, 0L, 0L, 0L);
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 작업 키·같은 지갑에 금액이 다른 요청이 동시에 들어오면 먼저 커밋한 하나만 반영되고 나머지는 충돌로 거절된다")
    void concurrentSameKeyDifferentAmounts() throws Exception {
        Long walletId = wallet(1L);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            long amountHp = i * 100L;
            tasks.add(() -> {
                try {
                    ledgerRecordUseCase.record(charge(walletId, "CHARGE:SAME", amountHp));
                    return amountHp;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    return null;
                }
            });
        }

        List<Long> applied = runTogether(tasks).stream().filter(amount -> amount != null).toList();

        assertThat(applied).hasSize(1);
        assertBalances(walletId, applied.getFirst(), 0L, 0L, 0L);
        assertThat(ledgerCount("CHARGE:SAME")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_hp FROM billing.point_ledger WHERE operation_key = 'CHARGE:SAME'", Long.class))
                .isEqualTo(applied.getFirst());
    }

    @Test
    @DisplayName("같은 거래를 다시 요청하면 원장과 잔액을 다시 바꾸지 않고 기존 원장 ID를 돌려준다")
    void retry_replays() {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 10_000L));
        LedgerOperationResult first = ledgerRecordUseCase.record(move(walletId, "HOLD:1001", LedgerTransactionType.HOLD,
                PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 3_000L));

        LedgerOperationResult retried = ledgerRecordUseCase.record(move(walletId, "HOLD:1001", LedgerTransactionType.HOLD,
                PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 3_000L));

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.ledgerIds()).isEqualTo(first.ledgerIds());
        assertThat(ledgerCount("HOLD:1001")).isEqualTo(2);
        assertBalances(walletId, 7_000L, 0L, 3_000L, 0L);
    }

    @Test
    @DisplayName("같은 작업 키로 금액·지갑·참조 대상·거래 유형이 다르면 IDEMPOTENCY_KEY_CONFLICT로 거절하고 잔액을 바꾸지 않는다")
    void sameKeyDifferentContent_conflict() {
        Long walletId = wallet(1L);
        Long otherWallet = wallet(2L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));

        assertBillingError(() -> ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 2_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> ledgerRecordUseCase.record(charge(otherWallet, "CHARGE:1", 1_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> ledgerRecordUseCase.record(new LedgerOperation("CHARGE:1", LedgerTransactionType.CHARGE,
                        BillingReferenceType.POINT_CHARGE, 2L, null, NOW,
                        List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L)))),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> ledgerRecordUseCase.record(operation("CHARGE:1", LedgerTransactionType.PAYOUT,
                        LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L))),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> ledgerRecordUseCase.record(operation("CHARGE:1", LedgerTransactionType.CHARGE,
                        LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 500L),
                        LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 500L))),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertBalances(walletId, 1_000L, 0L, 0L, 0L);
        assertBalances(otherWallet, 0L, 0L, 0L, 0L);
        assertThat(ledgerCount("CHARGE:1")).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 작업 키의 같은 내용 거래는 각각 반영된다")
    void differentKeys() {
        Long walletId = wallet(1L);

        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 1_000L));
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:2", 1_000L));

        assertBalances(walletId, 2_000L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("같은 거래가 동시에 여러 번 들어와도 한 번만 반영되고 나머지는 같은 결과를 재사용한다")
    void concurrentDuplicates_appliedOnce() throws Exception {
        Long walletId = wallet(1L);
        ledgerRecordUseCase.record(charge(walletId, "CHARGE:1", 10_000L));
        List<Callable<LedgerOperationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> ledgerRecordUseCase.record(move(walletId, "HOLD:1001", LedgerTransactionType.HOLD,
                    PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 3_000L)));
        }

        List<LedgerOperationResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(LedgerOperationResult::ledgerIds).containsOnly(results.getFirst().ledgerIds());
        assertThat(ledgerCount("HOLD:1001")).isEqualTo(2);
        assertBalances(walletId, 7_000L, 0L, 3_000L, 0L);
        assertMatched(walletId);
    }

    @Test
    @DisplayName("같은 작업 키를 서로 다른 지갑에 동시에 기록하면 하나만 반영되고 나머지는 충돌로 거절된다")
    void concurrentSameKeyDifferentWallets() throws Exception {
        List<Long> walletIds = new ArrayList<>();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Long walletId = wallet(100L + i);
            walletIds.add(walletId);
            tasks.add(() -> {
                try {
                    ledgerRecordUseCase.record(charge(walletId, "CHARGE:SHARED", 1_000L));
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(ledgerCount("CHARGE:SHARED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(charge_balance_hp) FROM billing.wallet", Long.class))
                .isEqualTo(1_000L);
        walletIds.forEach(this::assertMatched);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 HP 가용 잔액을 늘리는 충전 원장 기록 요청을 만든다.
    private LedgerOperation charge(Long walletId, String operationKey, long amountHp) {
        return operation(operationKey, LedgerTransactionType.CHARGE,
                LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp));
    }

    // 수익 HP 가용 잔액을 늘리는 지급 원장 기록 요청을 만든다.
    private LedgerOperation revenue(Long walletId, String operationKey, long amountHp) {
        return new LedgerOperation(operationKey, LedgerTransactionType.PAYOUT, BillingReferenceType.SETTLEMENT, 1L,
                null, NOW, List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp)));
    }

    // 같은 HP 종류 안에서 잔액 버킷을 옮기는 원장 기록 요청을 만든다.
    private LedgerOperation move(Long walletId, String operationKey, LedgerTransactionType transactionType,
            PointType pointType, BalanceBucket from, BalanceBucket to, long amountHp) {
        return new LedgerOperation(operationKey, transactionType, BillingReferenceType.LECTURE_ORDER, 1001L, null, NOW,
                LedgerEntry.move(walletId, pointType, from, to, amountHp));
    }

    // 작업 키·거래 유형·원장 줄로 원장 기록 요청을 만든다.
    private LedgerOperation operation(String operationKey, LedgerTransactionType transactionType, LedgerEntry... entries) {
        return new LedgerOperation(operationKey, transactionType, BillingReferenceType.POINT_CHARGE, 1L, null, NOW,
                List.of(entries));
    }

    // 지갑의 네 가지 캐시 잔액을 DB에서 다시 읽어 확인한다.
    private void assertBalances(Long walletId, long charge, long revenue, long heldCharge, long heldRevenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).as("chargeBalanceHp").isEqualTo(charge);
        assertThat(wallet.getRevenueBalanceHp()).as("revenueBalanceHp").isEqualTo(revenue);
        assertThat(wallet.getHeldChargeHp()).as("heldChargeHp").isEqualTo(heldCharge);
        assertThat(wallet.getHeldRevenueHp()).as("heldRevenueHp").isEqualTo(heldRevenue);
    }

    // 지갑 캐시와 원장 합계가 일치하는지 대사로 확인한다.
    private void assertMatched(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // 작업 키에 속한 원장 행 수를 센다.
    private int ledgerCount(String operationKey) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE operation_key = ?",
                Integer.class, operationKey);
    }

    // 지갑 잔액을 바꾸지 않고 원장 행만 직접 INSERT한다.
    private void insertLedgerRow(Long walletId, String operationKey, String idempotencyKey, long amountHp) {
        jdbcTemplate.update("""
                INSERT INTO billing.point_ledger (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                    reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                VALUES (?, 'CHARGE', 'AVAILABLE', ?, 'CHARGE', 'POINT_CHARGE', 1, ?, ?, now(), now())""",
                walletId, amountHp, operationKey, idempotencyKey);
    }

    // 실행이 BillingException으로 실패하고 그 오류 코드가 기대값인지 확인한다.
    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    // 다른 트랜잭션의 락을 기다리는 세션이 생길 때까지 최대 10초 기다린다.
    private void waitUntilLockWaiting() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("다른 트랜잭션이 지갑 락 대기 상태가 되지 않았습니다.");
    }

    // 래치가 열릴 때까지 최대 10초 기다린다.
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // 모든 작업을 같은 시점에 시작시키고 결과를 모으며, 작업 예외는 그대로 드러낸다.
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    throw new AssertionError("동시 작업이 실패했습니다.", exception.getCause());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
