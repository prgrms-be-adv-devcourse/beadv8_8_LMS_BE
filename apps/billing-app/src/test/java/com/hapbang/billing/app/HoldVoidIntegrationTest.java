package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

// 보류 무효화(#479)의 세 결과(RELEASED·ALREADY_CLOSED·VOIDED), 재요청, 무효화 뒤 보류 거절, 소유 확인,
// 같은 참조 대상의 보류·무효화 동시 실행을 실제 PostgreSQL로 검증한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273). 동시 실행 스레드에는 DelegatingSecurityContextExecutorService로 넘긴다
@WithMockUser(roles = "INTERNAL")
class HoldVoidIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final BillingReferenceType ORDER = BillingReferenceType.LECTURE_ORDER_ITEM;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    @DisplayName("남은 보류가 있으면 전부 반환하고 RELEASED와 반환한 HP를 돌려준다")
    void released() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 3_000L);
        HoldResult hold = hold(1L, 1001L, 3_000L);
        holdFacade.release(ReleaseCommand.partial(1L, ORDER, 1001L, "R1", 500L));

        HoldVoidResult result = voidHold(1L, 1001L);

        assertThat(result).isEqualTo(new HoldVoidResult(ORDER, 1001L, HoldVoidOutcome.RELEASED, 2_500L, false));
        assertThat(holdStatus(hold.holdId())).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM billing.point_hold_detail WHERE point_hold_id = ? ORDER BY id",
                String.class, hold.holdId()))
                .containsExactly("RELEASE:" + hold.holdId() + ":R1", "RELEASE:" + hold.holdId() + ":VOID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE operation_key = ?", Integer.class,
                "RELEASE:" + hold.holdId() + ":VOID")).isPositive();
        assertBalances(walletId, 3_000L, 1_000L);
        assertThat(voidRow(1001L)).isEqualTo("1 RELEASED 2500");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("이미 전부 반환한 보류면 바꾸지 않고 ALREADY_CLOSED와 0을 돌려준다")
    void alreadyClosed() {
        Long walletId = wallet(1L);
        charge(walletId, 2_000L);
        hold(1L, 1001L, 2_000L);
        holdFacade.release(ReleaseCommand.full(1L, ORDER, 1001L, "R1"));
        List<String> ledgers = ledgerKeys();

        HoldVoidResult result = voidHold(1L, 1001L);

        assertThat(result).isEqualTo(new HoldVoidResult(ORDER, 1001L, HoldVoidOutcome.ALREADY_CLOSED, 0L, false));
        assertThat(ledgerKeys()).isEqualTo(ledgers);
        assertBalances(walletId, 2_000L, 0L);
        assertThat(voidRow(1001L)).isEqualTo("1 ALREADY_CLOSED 0");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("보류가 없으면 무효 표시만 남기고 VOIDED를 돌려주며, 뒤늦게 온 보류는 HOLD_VOIDED로 거절한다")
    void voidedBlocksLateHold() {
        Long walletId = wallet(1L);
        charge(walletId, 2_000L);

        HoldVoidResult result = voidHold(1L, 1001L);

        assertThat(result).isEqualTo(new HoldVoidResult(ORDER, 1001L, HoldVoidOutcome.VOIDED, 0L, false));
        assertBillingError(() -> hold(1L, 1001L, 1_000L), BillingErrorCode.HOLD_VOIDED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class)).isZero();
        assertBalances(walletId, 2_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("무효화 전에 만든 보류의 같은 보류 재요청도 HOLD_VOIDED로 거절한다")
    void holdRetryAfterVoidRejected() {
        Long walletId = wallet(1L);
        charge(walletId, 2_000L);
        hold(1L, 1001L, 1_000L);
        voidHold(1L, 1001L);

        assertBillingError(() -> hold(1L, 1001L, 1_000L), BillingErrorCode.HOLD_VOIDED);
        assertBalances(walletId, 2_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 무효화를 다시 보내면 처음 결과를 replayed로 돌려주고 HP를 다시 옮기지 않는다")
    void replay() {
        Long walletId = wallet(1L);
        charge(walletId, 2_000L);
        hold(1L, 1001L, 1_500L);
        HoldVoidResult first = voidHold(1L, 1001L);
        List<String> ledgers = ledgerKeys();

        HoldVoidResult second = voidHold(1L, 1001L);

        assertThat(first.replayed()).isFalse();
        assertThat(second).isEqualTo(new HoldVoidResult(ORDER, 1001L, HoldVoidOutcome.RELEASED, 1_500L, true));
        assertThat(ledgerKeys()).isEqualTo(ledgers);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.hold_void", Integer.class)).isEqualTo(1);
        assertBalances(walletId, 2_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("다른 회원의 보류나 무효화 기록이면 HOLD_OWNER_MISMATCH로 거절하고 아무것도 바꾸지 않는다")
    void ownerMismatch() {
        Long walletId = wallet(1L);
        wallet(2L);
        charge(walletId, 2_000L);
        hold(1L, 1001L, 1_000L);
        voidHold(1L, 1002L);

        assertBillingError(() -> voidHold(2L, 1001L), BillingErrorCode.HOLD_OWNER_MISMATCH);
        assertBillingError(() -> voidHold(2L, 1002L), BillingErrorCode.HOLD_OWNER_MISMATCH);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.hold_void", Integer.class)).isEqualTo(1);
        assertThat(walletRepository.findById(walletId).orElseThrow().getHeldChargeHp()).isEqualTo(1_000L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("지갑이 없는 회원의 무효화는 WALLET_NOT_FOUND로 거절한다")
    void walletNotFound() {
        assertBillingError(() -> voidHold(9L, 1001L), BillingErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 참조 대상의 보류와 무효화가 동시에 와도 보류가 남은 채 무효화되는 일이 없다")
    void concurrentHoldAndVoid() throws Exception {
        Long walletId = wallet(1L);
        charge(walletId, 20_000L);

        for (long referenceId = 2001L; referenceId <= 2010L; referenceId++) {
            long reference = referenceId;
            List<String> results = runTogether(List.of(
                    () -> {
                        try {
                            hold(1L, reference, 1_000L);
                            return "HELD";
                        } catch (BillingException exception) {
                            return exception.getErrorCode().name();
                        }
                    },
                    () -> voidHold(1L, reference).outcome().name()));

            assertThat(results).isIn(List.of("HELD", "RELEASED"), List.of("HOLD_VOIDED", "VOIDED"));
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_hold WHERE status IN ('HELD', 'PARTIALLY_PROCESSED')""",
                Integer.class)).isZero();
        assertBalances(walletId, 20_000L, 0L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private HoldResult hold(Long userId, Long referenceId, long amountHp) {
        return holdFacade.hold(new HoldCommand(userId, ORDER, referenceId, amountHp));
    }

    private HoldVoidResult voidHold(Long userId, Long referenceId) {
        return holdFacade.voidHold(new HoldVoidCommand(userId, ORDER, referenceId));
    }

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다.
    private void charge(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, ?, ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + sequence.incrementAndGet(),
                PointChargeStatus.COMPLETED.name(), Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }

    // 수익 HP 가용 잔액을 늘리는 지급 원장을 기록한다.
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private String holdStatus(Long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM billing.point_hold WHERE id = ?", String.class, holdId);
    }

    // 참조 대상의 무효화 기록을 "회원ID 결과 반환액" 형식으로 읽는다.
    private String voidRow(Long referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT user_id || ' ' || outcome || ' ' || released_hp FROM billing.hold_void
                WHERE reference_type = ? AND reference_id = ?""", String.class, ORDER.name(), referenceId);
    }

    private List<String> ledgerKeys() {
        return jdbcTemplate.queryForList("SELECT idempotency_key FROM billing.point_ledger ORDER BY id", String.class);
    }

    // 지갑의 가용 잔액을 확인하고 보류 잔액이 0인지 확인한다.
    private void assertBalances(Long walletId, long charge, long revenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).as("chargeBalanceHp").isEqualTo(charge);
        assertThat(wallet.getRevenueBalanceHp()).as("revenueBalanceHp").isEqualTo(revenue);
        assertThat(wallet.getHeldChargeHp()).as("heldChargeHp").isZero();
        assertThat(wallet.getHeldRevenueHp()).as("heldRevenueHp").isZero();
    }

    // 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류 합계 = 보류 잔액.
    private void assertInvariants(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("원장 대사").isTrue();
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(remaining_principal_hp), 0) FROM billing.point_charge
                WHERE wallet_id = ? AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED')""", Long.class, walletId))
                .as("충전 원금 합계 = 충전 HP 가용 잔액").isEqualTo(wallet.getChargeBalanceHp());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(held_charge_hp + held_revenue_hp), 0) FROM billing.point_hold WHERE wallet_id = ?""",
                Long.class, walletId)).as("보류 합계").isEqualTo(wallet.getHeldChargeHp() + wallet.getHeldRevenueHp());
    }

    // 실행이 BillingException으로 실패하고 그 오류 코드가 기대값인지 확인한다.
    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    // 모든 작업을 같은 시점에 시작시키고 결과를 모으며, 작업 예외는 그대로 드러낸다.
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(tasks.size()));
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
