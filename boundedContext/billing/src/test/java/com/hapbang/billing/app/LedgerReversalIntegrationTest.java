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

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 원장 단위 역분개 정책(부분·누적 한도·원본 일치·역분개의 역분개 금지·롤백)을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class LedgerReversalIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 원본을 서로 다른 작업 키로 여러 번 부분 역분개할 수 있고, 누적이 원본을 넘으면 INVALID_REVERSAL로 거절한다")
    void partialReversals_upToOriginal() {
        Long walletId = wallet(1L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);

        reverse("REVERSAL:1", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -300L, originalId);
        reverse("REVERSAL:2", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -700L, originalId);
        assertBillingError(() -> reverse("REVERSAL:3", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1L, originalId),
                BillingErrorCode.INVALID_REVERSAL);

        assertThat(chargeBalance(walletId)).isZero();
        assertThat(reversedAmount(originalId)).isEqualTo(-1_000L);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("한 작업에서 같은 원본을 여러 줄로 역분개해도 누적 한도를 함께 검사한다")
    void reversalLinesInSameOperation_shareLimit() {
        Long walletId = wallet(1L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);

        assertBillingError(() -> ledgerRecordUseCase.record(reversal("REVERSAL:1",
                        new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -600L, originalId),
                        new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -600L, originalId))),
                BillingErrorCode.INVALID_REVERSAL);

        assertThat(chargeBalance(walletId)).isEqualTo(1_000L);
        assertThat(reversedAmount(originalId)).isZero();
    }

    @Test
    @DisplayName("원본과 다른 지갑·HP 종류·잔액 버킷이나 같은 부호의 역분개는 INVALID_REVERSAL로 거절한다")
    void mismatchedReversal() {
        Long walletId = wallet(1L);
        Long otherWallet = wallet(2L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);
        charge(otherWallet, "CHARGE:2", 1_000L);

        assertBillingError(() -> reverse("R:WALLET", otherWallet, PointType.CHARGE, BalanceBucket.AVAILABLE, -100L, originalId),
                BillingErrorCode.INVALID_REVERSAL);
        assertBillingError(() -> reverse("R:TYPE", walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, -100L, originalId),
                BillingErrorCode.INVALID_REVERSAL);
        assertBillingError(() -> reverse("R:BUCKET", walletId, PointType.CHARGE, BalanceBucket.HELD, -100L, originalId),
                BillingErrorCode.INVALID_REVERSAL);
        assertBillingError(() -> reverse("R:SIGN", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 100L, originalId),
                BillingErrorCode.INVALID_REVERSAL);

        assertThat(chargeBalance(walletId)).isEqualTo(1_000L);
        assertThat(chargeBalance(otherWallet)).isEqualTo(1_000L);
        assertThat(reversedAmount(originalId)).isZero();
    }

    @Test
    @DisplayName("역분개 원장을 원본으로 하는 역분개의 역분개는 INVALID_REVERSAL로 거절한다")
    void reversalOfReversal() {
        Long walletId = wallet(1L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);
        Long reversalId = reverse("REVERSAL:1", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -400L, originalId);

        assertBillingError(() -> reverse("REVERSAL:2", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 400L, reversalId),
                BillingErrorCode.INVALID_REVERSAL);

        assertThat(chargeBalance(walletId)).isEqualTo(600L);
    }

    @Test
    @DisplayName("여러 줄 원장(버킷 이동)의 한 줄만 개별로 역분개할 수 있다")
    void reverseSingleLineOfMultiLineOperation() {
        Long walletId = wallet(1L);
        charge(walletId, "CHARGE:1", 1_000L);
        List<Long> holdIds = ledgerRecordUseCase.record(new LedgerOperation("HOLD:1", LedgerTransactionType.HOLD,
                BillingReferenceType.LECTURE_ORDER, 1L, null, NOW,
                LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 1_000L)))
                .ledgerIds();

        reverse("REVERSAL:HOLD:1:2", walletId, PointType.CHARGE, BalanceBucket.HELD, -1_000L, holdIds.get(1));

        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).isZero();
        assertThat(wallet.getHeldChargeHp()).isZero();
        assertThat(reversedAmount(holdIds.get(0))).isZero();
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("역분개로 잔액이 음수가 되면 INSUFFICIENT_BALANCE로 거절하고 같은 작업의 다른 역분개 줄까지 모두 롤백한다")
    void reversalMakingNegativeBalance_rollsBack() {
        Long walletId = wallet(1L);
        Long chargeId = charge(walletId, "CHARGE:1", 1_000L);
        Long revenueId = ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:1", LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, 1L, null, NOW,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 500L)))).ledgerIds().getFirst();
        // 충전 HP 1,000 중 900을 보류해 가용은 100만 남는다.
        ledgerRecordUseCase.record(new LedgerOperation("HOLD:1", LedgerTransactionType.HOLD,
                BillingReferenceType.LECTURE_ORDER, 1L, null, NOW,
                LedgerEntry.move(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, BalanceBucket.HELD, 900L)));

        assertBillingError(() -> ledgerRecordUseCase.record(reversal("REVERSAL:1",
                        new LedgerEntry(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, -500L, revenueId),
                        new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1_000L, chargeId))),
                BillingErrorCode.INSUFFICIENT_BALANCE);

        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getRevenueBalanceHp()).isEqualTo(500L);
        assertThat(wallet.getChargeBalanceHp()).isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE operation_key = 'REVERSAL:1'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("같은 원본의 부분 역분개가 서로 다른 작업 키로 동시에 들어와도 누적이 원본을 넘지 않는다")
    void concurrentReversals_doNotExceedOriginal() throws Exception {
        Long walletId = wallet(1L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "REVERSAL:" + i;
            tasks.add(() -> {
                try {
                    reverse(key, walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -300L, originalId);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INVALID_REVERSAL);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(3);
        assertThat(reversedAmount(originalId)).isEqualTo(-900L);
        assertThat(chargeBalance(walletId)).isEqualTo(100L);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("역분개도 같은 작업 키로 재요청하면 다시 반영하지 않고 기존 결과를 재사용한다")
    void reversalRetry_replays() {
        Long walletId = wallet(1L);
        Long originalId = charge(walletId, "CHARGE:1", 1_000L);
        Long reversalId = reverse("REVERSAL:1", walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1_000L, originalId);

        LedgerOperationResult retried = ledgerRecordUseCase.record(reversal("REVERSAL:1",
                new LedgerEntry(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, -1_000L, originalId)));

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.ledgerIds()).containsExactly(reversalId);
        assertThat(reversedAmount(originalId)).isEqualTo(-1_000L);
    }

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 HP 가용 잔액을 늘리고 원장 ID를 반환한다.
    private Long charge(Long walletId, String operationKey, long amountHp) {
        return ledgerRecordUseCase.record(new LedgerOperation(operationKey, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, 1L, null, NOW,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp)))).ledgerIds().getFirst();
    }

    // 원본 원장 한 줄을 역분개하고 역분개 원장 ID를 반환한다.
    private Long reverse(String operationKey, Long walletId, PointType pointType, BalanceBucket balanceBucket,
            long amountHp, Long originalLedgerId) {
        return ledgerRecordUseCase.record(reversal(operationKey,
                new LedgerEntry(walletId, pointType, balanceBucket, amountHp, originalLedgerId))).ledgerIds().getFirst();
    }

    // 원장 줄로 역분개 원장 기록 요청을 만든다.
    private LedgerOperation reversal(String operationKey, LedgerEntry... entries) {
        return new LedgerOperation(operationKey, LedgerTransactionType.REVERSAL, BillingReferenceType.POINT_CHARGE, 1L,
                "정정", NOW, List.of(entries));
    }

    // 지갑의 충전 HP 가용 잔액을 DB에서 다시 읽는다.
    private long chargeBalance(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow().getChargeBalanceHp();
    }

    // 원본 원장을 참조하는 역분개 금액의 합계를 DB에서 직접 구한다.
    private long reversedAmount(Long originalLedgerId) {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger WHERE original_ledger_id = ?",
                Long.class, originalLedgerId);
    }

    // 실행이 BillingException으로 실패하고 그 오류 코드가 기대값인지 확인한다.
    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
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
