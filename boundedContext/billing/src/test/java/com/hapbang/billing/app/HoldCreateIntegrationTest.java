package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.HoldResult.ChargeAllocation;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// HP 보류 생성의 수익 HP 우선·충전 HP FIFO 배분, 보류·배분·원장·지갑 정합성, 멱등성, 롤백, 동시성을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class HoldCreateIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T00:00:00Z");
    private static final String ORDER_KEY = "HOLD:LECTURE_ORDER_ITEM:1001";
    private static final int THREADS = 8;

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

    @Autowired
    PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 배분 정책
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("수익 HP가 충분하면 수익 HP만 보류하고 충전 HP·충전 원금은 쓰지 않는다")
    void revenueOnly() {
        Long walletId = wallet(1L);
        revenue(walletId, 5_000L);
        Long chargeId = charge(walletId, 3_000L, T1);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 4_000L);

        assertThat(result.revenueHp()).isEqualTo(4_000L);
        assertThat(result.chargeHp()).isZero();
        assertThat(result.chargeAllocations()).isEmpty();
        assertThat(result.replayed()).isFalse();
        assertBalances(walletId, 3_000L, 1_000L, 0L, 4_000L);
        assertThat(remainingPrincipal(chargeId)).isEqualTo(3_000L);
        assertThat(usageCount(result.holdId())).isZero();
        assertThat(ledgerLines(ORDER_KEY)).containsExactly(
                "HOLD:LECTURE_ORDER_ITEM:1001:1 REVENUE AVAILABLE -4000",
                "HOLD:LECTURE_ORDER_ITEM:1001:2 REVENUE HELD 4000");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("수익 HP가 없으면 충전 HP만 보류하고 충전 건 원금을 앞에서부터 나눠 배분한다")
    void chargeOnly_splitAcrossCharges() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 4_000L, T2);
        Long third = charge(walletId, 5_000L, T3);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L);

        assertThat(result.revenueHp()).isZero();
        assertThat(result.chargeHp()).isEqualTo(5_000L);
        assertThat(result.chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 2_000L), new ChargeAllocation(second, 3_000L));
        assertThat(remainingPrincipal(first)).isZero();
        assertThat(remainingPrincipal(second)).isEqualTo(1_000L);
        assertThat(remainingPrincipal(third)).isEqualTo(5_000L);
        assertBalances(walletId, 6_000L, 0L, 5_000L, 0L);
        assertThat(ledgerLines(ORDER_KEY)).containsExactly(
                "HOLD:LECTURE_ORDER_ITEM:1001:1 CHARGE AVAILABLE -5000",
                "HOLD:LECTURE_ORDER_ITEM:1001:2 CHARGE HELD 5000");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("수익 HP가 부족하면 수익 HP를 전부 먼저 보류하고 부족분만 충전 HP로 보류하며 HP 총량은 변하지 않는다")
    void revenueFirstThenCharge() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        Long first = charge(walletId, 4_000L, T1);
        Long second = charge(walletId, 6_000L, T2);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 8_000L);

        assertThat(result.totalHp()).isEqualTo(8_000L);
        assertThat(result.revenueHp()).isEqualTo(3_000L);
        assertThat(result.chargeHp()).isEqualTo(5_000L);
        assertThat(result.chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 4_000L), new ChargeAllocation(second, 1_000L));
        assertBalances(walletId, 5_000L, 0L, 5_000L, 3_000L);
        assertThat(totalHp(walletId)).isEqualTo(13_000L);
        // 원장은 수익 HP 이동 2줄, 충전 HP 이동 2줄을 같은 작업 키로 남긴다.
        assertThat(ledgerLines(ORDER_KEY)).containsExactly(
                "HOLD:LECTURE_ORDER_ITEM:1001:1 REVENUE AVAILABLE -3000",
                "HOLD:LECTURE_ORDER_ITEM:1001:2 REVENUE HELD 3000",
                "HOLD:LECTURE_ORDER_ITEM:1001:3 CHARGE AVAILABLE -5000",
                "HOLD:LECTURE_ORDER_ITEM:1001:4 CHARGE HELD 5000");
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT transaction_type || ' ' || reference_type || ' ' || reference_id
                FROM billing.point_ledger WHERE operation_key = ?""", String.class, ORDER_KEY))
                .containsExactly("HOLD LECTURE_ORDER_ITEM 1001");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("최초 보류는 HELD 상태이고 남은 보류액이 최초 보류액과 같으며, 충전 건별 배분이 POINT_CHARGE_USAGE에 남는다")
    void holdAndUsageRows() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 4_000L, T2);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 6_000L);

        Map<String, Object> hold = jdbcTemplate.queryForMap("SELECT * FROM billing.point_hold WHERE id = ?",
                result.holdId());
        assertThat(hold)
                .containsEntry("wallet_id", walletId)
                .containsEntry("reference_type", "LECTURE_ORDER_ITEM")
                .containsEntry("reference_id", 1001L)
                .containsEntry("original_revenue_hp", 3_000L)
                .containsEntry("original_charge_hp", 3_000L)
                .containsEntry("held_revenue_hp", 3_000L)
                .containsEntry("held_charge_hp", 3_000L)
                .containsEntry("status", "HELD")
                .containsEntry("idempotency_key", ORDER_KEY)
                .containsEntry("released_at", null)
                .containsEntry("canceled_at", null);
        assertThat(hold.get("held_at")).isNotNull();
        // 이 보류가 취소되면 이 두 행으로 원래 충전 건에 정확히 되돌릴 수 있다.
        assertThat(jdbcTemplate.queryForList("""
                SELECT charge_id || ':' || amount_hp || ':' || remaining_held_hp || ':' || consumed_hp || ':'
                    || released_hp || ':' || status
                FROM billing.point_charge_usage WHERE point_hold_id = ? ORDER BY id""", String.class, result.holdId()))
                .containsExactly(first + ":2000:2000:0:0:HELD", second + ":1000:1000:0:0:HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isZero();
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("충전 건은 ID가 아니라 완료 시각이 이른 것부터 배분한다")
    void fifoByCompletedAt() {
        Long walletId = wallet(1L);
        Long late = charge(walletId, 2_000L, T2);
        Long early = charge(walletId, 2_000L, T1);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);

        assertThat(late).isLessThan(early);
        assertThat(result.chargeAllocations()).containsExactly(
                new ChargeAllocation(early, 2_000L), new ChargeAllocation(late, 1_000L));
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("완료 시각이 같으면 충전 ID가 작은 것부터 배분한다")
    void fifoSameCompletedAt_byId() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 2_000L, T1);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);

        assertThat(result.chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 2_000L), new ChargeAllocation(second, 1_000L));
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("완료(COMPLETED)·부분 환불(PARTIALLY_REFUNDED) 건의 가용 원금만 배분하고 대기·실패·환불 완료 건은 배분하지 않는다")
    void allocatableStatusesOnly() {
        Long walletId = wallet(1L);
        Long pending = insertCharge(walletId, 5_000L, 5_000L, PointChargeStatus.PENDING, null);
        Long failed = insertCharge(walletId, 5_000L, 5_000L, PointChargeStatus.FAILED, null);
        Long refunded = insertCharge(walletId, 5_000L, 0L, PointChargeStatus.REFUNDED, T1);
        Long partiallyRefunded = chargeWith(walletId, 3_000L, 1_000L, PointChargeStatus.PARTIALLY_REFUNDED, T1);
        Long completed = charge(walletId, 2_000L, T2);

        HoldResult result = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 2_500L);

        assertThat(result.chargeAllocations()).containsExactly(
                new ChargeAllocation(partiallyRefunded, 1_000L), new ChargeAllocation(completed, 1_500L));
        assertThat(remainingPrincipal(pending)).isEqualTo(5_000L);
        assertThat(remainingPrincipal(failed)).isEqualTo(5_000L);
        assertThat(remainingPrincipal(refunded)).isZero();
        assertBalances(walletId, 500L, 0L, 2_500L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("이전 보류가 있으면 보류 잔액에 누적되고, 다음 보류는 남은 충전 원금부터 이어서 배분한다")
    void accumulatesOnPreviousHold() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 5_000L, T1);
        Long second = charge(walletId, 5_000L, T2);

        hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);
        HoldResult next = hold(1L, BillingReferenceType.APPEARANCE_REQUEST, 7L, 4_000L);

        assertThat(next.chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 2_000L), new ChargeAllocation(second, 2_000L));
        assertBalances(walletId, 3_000L, 0L, 7_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("가용 수익 HP와 충전 HP를 합쳐도 모자라면 INSUFFICIENT_BALANCE로 거절하고 아무것도 바꾸지 않는다")
    void insufficientBalance() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 2_000L, T1);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_001L),
                BillingErrorCode.INSUFFICIENT_BALANCE);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("충전 원금 합계가 지갑 충전 HP 잔액과 다르면 CHARGE_PRINCIPAL_MISMATCH로 거절하고 아무것도 바꾸지 않는다")
    void chargePrincipalMismatch() {
        Long walletId = wallet(1L);
        charge(walletId, 2_000L, T1);
        revenue(walletId, 5_000L);
        // 충전 건 없이 충전 원장만 기록해 지갑 충전 HP(3,000)와 충전 원금 합계(2,000)를 어긋나게 만든다.
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:ORPHAN", LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, 999L, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L))));
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L),
                BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        // 수익 HP만으로 충분한 보류도 불일치 상태에서는 진행하지 않는다.
        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1002L, 1_000L),
                BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("결제 회원의 지갑이 없으면 지갑을 만들지 않고 WALLET_NOT_FOUND로 거절한다")
    void walletNotFound() {
        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 1_000L),
                BillingErrorCode.WALLET_NOT_FOUND);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet", Integer.class)).isZero();
        assertThat(holdCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 참조·지갑·금액으로 다시 요청하면 보류·배분·원장·지갑·충전 원금을 바꾸지 않고 처음 결과를 돌려준다")
    void retry_replays() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        charge(walletId, 4_000L, T1);
        charge(walletId, 6_000L, T2);
        HoldResult first = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 8_000L);
        Map<String, Object> before = snapshot();

        HoldResult retried = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 8_000L);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ledgerLines(ORDER_KEY)).hasSize(4);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("보류 후 잔액이 바뀐 뒤 재요청해도 다시 배분하지 않고 처음 배분 결과를 돌려준다")
    void retryAfterBalanceChange_keepsOriginalSplit() {
        Long walletId = wallet(1L);
        Long chargeId = charge(walletId, 5_000L, T1);
        HoldResult first = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);
        revenue(walletId, 10_000L);

        HoldResult retried = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.revenueHp()).isZero();
        assertThat(retried.chargeAllocations()).containsExactly(new ChargeAllocation(chargeId, 3_000L));
        assertThat(retried.holdId()).isEqualTo(first.holdId());
        assertBalances(walletId, 2_000L, 10_000L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 참조 대상에 다른 금액으로 요청하면 IDEMPOTENCY_KEY_CONFLICT로 거절하고 아무것도 바꾸지 않는다")
    void sameReferenceDifferentAmount_conflict() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 4_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 참조 대상에 다른 지갑(회원)으로 요청하면 IDEMPOTENCY_KEY_CONFLICT로 거절하고 아무것도 바꾸지 않는다")
    void sameReferenceDifferentWallet_conflict() {
        Long walletId = wallet(1L);
        Long otherWalletId = wallet(2L);
        charge(walletId, 10_000L, T1);
        charge(otherWalletId, 10_000L, T1);
        hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(2L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(otherWalletId);
    }

    @Test
    @DisplayName("참조 유형이나 참조 ID가 다르면 다른 보류로 각각 만든다")
    void differentReference_newHold() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);

        HoldResult order = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 1_000L);
        HoldResult otherOrder = hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1002L, 1_000L);
        HoldResult request = hold(1L, BillingReferenceType.APPEARANCE_REQUEST, 1001L, 1_000L);
        HoldResult recruit = hold(1L, BillingReferenceType.APPEARANCE_RECRUIT, 1001L, 1_000L);

        assertThat(List.of(order.holdId(), otherOrder.holdId(), request.holdId(), recruit.holdId()))
                .doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForList("SELECT idempotency_key FROM billing.point_hold ORDER BY id", String.class))
                .containsExactly("HOLD:LECTURE_ORDER_ITEM:1001", "HOLD:LECTURE_ORDER_ITEM:1002",
                        "HOLD:APPEARANCE_REQUEST:1001", "HOLD:APPEARANCE_RECRUIT:1001");
        assertBalances(walletId, 6_000L, 0L, 4_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("보류 없이 같은 작업 키의 원장만 있으면 정상 재요청으로 보지 않고 HOLD_STATE_INCONSISTENT로 거절한다")
    void ledgerWithoutHold_inconsistent() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        insertLedgerRow(walletId, ORDER_KEY, ORDER_KEY + ":1");
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("보류는 있는데 원장이 없으면 재요청을 HOLD_STATE_INCONSISTENT로 거절한다")
    void holdWithoutLedger_inconsistent() {
        Long walletId = wallet(1L);
        revenue(walletId, 5_000L);
        jdbcTemplate.update("""
                INSERT INTO billing.point_hold (wallet_id, reference_type, reference_id, original_charge_hp,
                    original_revenue_hp, held_charge_hp, held_revenue_hp, status, idempotency_key, held_at,
                    created_at, updated_at)
                VALUES (?, 'LECTURE_ORDER_ITEM', 1001, 0, 1000, 0, 1000, 'HELD', ?, now(), now(), now())""",
                walletId, ORDER_KEY);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 1_000L),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);

        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // 트랜잭션
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("원장 저장이 실패하면 보류·배분·충전 원금·지갑 변경이 모두 롤백된다")
    void ledgerFailure_rollsBackAll() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        charge(walletId, 10_000L, T1);
        // 다른 작업이 이 보류의 3번째 원장 멱등키를 이미 쓰고 있어 충전 HP 이동 INSERT가 UNIQUE 충돌로 실패한다.
        insertLedgerRow(walletId, "OTHER", ORDER_KEY + ":3");
        Map<String, Object> before = snapshot();

        assertBillingError(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 8_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("보류 저장이 실패하면 아무것도 바뀌지 않는다")
    void holdInsertFailure_rollsBackAll() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        Map<String, Object> before = snapshot();

        failOn("point_hold", "INSERT",
                () -> assertThatThrownBy(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L))
                        .hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("충전 원금 변경이 실패하면 먼저 저장한 보류와 배분까지 모두 롤백된다")
    void chargeUpdateFailure_rollsBackAll() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        Map<String, Object> before = snapshot();

        failOn("point_charge", "UPDATE",
                () -> assertThatThrownBy(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L))
                        .hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("충전 원금 배분 내역 저장이 실패하면 먼저 저장한 보류까지 모두 롤백된다")
    void usageInsertFailure_rollsBackAll() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        Map<String, Object> before = snapshot();

        failOn("point_charge_usage", "INSERT",
                () -> assertThatThrownBy(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L))
                        .hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션에 참여하며, 그 트랜잭션이 롤백되면 보류도 모두 롤백된다")
    void joinsCallerTransaction() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        Map<String, Object> before = snapshot();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 3_000L);
            throw new IllegalStateException("이후 업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("한 트랜잭션에서 보류를 연속으로 만들어도 앞 보류의 미반영 변경을 잃지 않고 FIFO를 이어간다")
    void consecutiveHoldsInSameTransaction() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        List<HoldResult> results = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            results.add(hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 2_000L));
            results.add(hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1002L, 2_000L));
            results.add(hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 2_000L));
        });

        assertThat(results.get(0).revenueHp()).isEqualTo(1_000L);
        assertThat(results.get(0).chargeAllocations()).containsExactly(new ChargeAllocation(first, 1_000L));
        assertThat(results.get(1).revenueHp()).isZero();
        assertThat(results.get(1).chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 1_000L), new ChargeAllocation(second, 1_000L));
        assertThat(results.get(2).replayed()).isTrue();
        assertThat(results.get(2).holdId()).isEqualTo(results.get(0).holdId());
        assertBalances(walletId, 2_000L, 0L, 3_000L, 1_000L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 보류 요청이 동시에 여러 번 들어와도 보류는 하나만 생기고 나머지는 같은 결과를 돌려받는다")
    void concurrentSameRequest_oneHold() throws Exception {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 3_000L, T1);
        charge(walletId, 3_000L, T2);
        List<Callable<HoldResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 1001L, 5_000L));
        }

        List<HoldResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(HoldResult::holdId).containsOnly(results.getFirst().holdId());
        assertThat(holdCount()).isEqualTo(1);
        assertThat(usageCount(results.getFirst().holdId())).isEqualTo(2);
        assertThat(ledgerLines(ORDER_KEY)).hasSize(4);
        assertBalances(walletId, 2_000L, 0L, 4_000L, 1_000L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 참조 대상을 서로 다른 지갑이 동시에 보류하면 하나만 성공하고 나머지는 충돌로 거절되며 잔액이 바뀌지 않는다")
    void concurrentSameReferenceDifferentWallets() throws Exception {
        List<Long> walletIds = new ArrayList<>();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long userId = 100L + i;
            Long walletId = wallet(userId);
            charge(walletId, 5_000L, T1);
            walletIds.add(walletId);
            tasks.add(() -> {
                try {
                    hold(userId, BillingReferenceType.APPEARANCE_RECRUIT, 7L, 3_000L);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(holdCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge_usage", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(held_charge_hp) FROM billing.wallet", Long.class))
                .isEqualTo(3_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(remaining_principal_hp) FROM billing.point_charge",
                Long.class)).isEqualTo(THREADS * 5_000L - 3_000L);
        walletIds.forEach(this::assertInvariants);
    }

    @Test
    @DisplayName("같은 지갑에 서로 다른 보류가 동시에 들어와도 충전 원금을 이중 배분하지 않고 가용 HP만큼만 성공한다")
    void concurrentDistinctHolds_noDoubleAllocation() throws Exception {
        Long walletId = wallet(1L);
        List<Long> chargeIds = List.of(charge(walletId, 1_000L, T1), charge(walletId, 1_000L, T2),
                charge(walletId, 1_000L, T3));
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long orderId = 2000L + i;
            tasks.add(() -> {
                try {
                    hold(1L, BillingReferenceType.LECTURE_ORDER_ITEM, orderId, 500L);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(6);
        assertThat(holdCount()).isEqualTo(6);
        for (Long chargeId : chargeIds) {
            assertThat(remainingPrincipal(chargeId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT sum(amount_hp) FROM billing.point_charge_usage WHERE charge_id = ?", Long.class, chargeId))
                    .isEqualTo(1_000L);
        }
        assertBalances(walletId, 0L, 0L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private HoldResult hold(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp) {
        return holdFacade.hold(new HoldCommand(userId, referenceType, referenceId, amountHp));
    }

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다.
    private Long charge(Long walletId, long amountHp, Instant completedAt) {
        return chargeWith(walletId, amountHp, amountHp, PointChargeStatus.COMPLETED, completedAt);
    }

    // 지정 상태·가용 원금의 충전 건과 가용 원금만큼의 충전 원장을 함께 만든다.
    private Long chargeWith(Long walletId, long amountHp, long remainingHp, PointChargeStatus status,
            Instant completedAt) {
        Long chargeId = insertCharge(walletId, amountHp, remainingHp, status, completedAt);
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, completedAt,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, remainingHp))));
        return chargeId;
    }

    // 원장 없이 충전 건 행만 INSERT한다 (충전 API는 아직 없다).
    private Long insertCharge(Long walletId, long amountHp, long remainingHp, PointChargeStatus status,
            Instant completedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, ?, ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, remainingHp, "order-" + sequence.incrementAndGet(), status.name(),
                Timestamp.from(T1), completedAt == null ? null : Timestamp.from(completedAt));
    }

    // 수익 HP 가용 잔액을 늘리는 지급 원장을 기록한다.
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    // 지갑 잔액을 바꾸지 않고 원장 행만 직접 INSERT한다.
    private void insertLedgerRow(Long walletId, String operationKey, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO billing.point_ledger (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                    reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                VALUES (?, 'CHARGE', 'AVAILABLE', 1, 'CHARGE', 'POINT_CHARGE', 1, ?, ?, now(), now())""",
                walletId, operationKey, idempotencyKey);
    }

    // 테이블에 행 단위 실패 트리거를 잠시 걸고 동작을 실행한 뒤 트리거를 지운다.
    // 실패는 SQL 실행 위치(저장소 호출 또는 record()의 flush)에 따라 Spring 또는 Hibernate 예외로 감싸지므로 메시지로 확인한다.
    private void failOn(String table, String event, Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail BEFORE " + event + " ON billing." + table
                + " FOR EACH ROW EXECUTE FUNCTION billing.test_fail()");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing." + table);
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    // 지갑 잔액·충전 원금·보류·배분·원장 상태를 비교할 수 있게 모은다.
    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("charges", jdbcTemplate.queryForList(
                "SELECT id, remaining_principal_hp FROM billing.point_charge ORDER BY id"));
        snapshot.put("holds", jdbcTemplate.queryForList("""
                SELECT id, wallet_id, original_charge_hp, original_revenue_hp, held_charge_hp, held_revenue_hp, status
                FROM billing.point_hold ORDER BY id"""));
        snapshot.put("usages", jdbcTemplate.queryForList(
                "SELECT id, charge_id, point_hold_id, amount_hp, remaining_held_hp FROM billing.point_charge_usage ORDER BY id"));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    // 지갑의 네 가지 캐시 잔액을 DB에서 다시 읽어 확인한다.
    private void assertBalances(Long walletId, long charge, long revenue, long heldCharge, long heldRevenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).as("chargeBalanceHp").isEqualTo(charge);
        assertThat(wallet.getRevenueBalanceHp()).as("revenueBalanceHp").isEqualTo(revenue);
        assertThat(wallet.getHeldChargeHp()).as("heldChargeHp").isEqualTo(heldCharge);
        assertThat(wallet.getHeldRevenueHp()).as("heldRevenueHp").isEqualTo(heldRevenue);
    }

    // 보류 정합성 불변식: 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류 합계 = 보류 잔액, 보류별 충전 HP = 배분 합계.
    private void assertInvariants(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("원장 대사").isTrue();
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(remaining_principal_hp), 0) FROM billing.point_charge
                WHERE wallet_id = ? AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED')""", Long.class, walletId))
                .as("충전 원금 합계 = 충전 HP 가용 잔액").isEqualTo(wallet.getChargeBalanceHp());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(held_charge_hp), 0) FROM billing.point_hold WHERE wallet_id = ?""",
                Long.class, walletId)).as("보류 충전 HP 합계").isEqualTo(wallet.getHeldChargeHp());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(held_revenue_hp), 0) FROM billing.point_hold WHERE wallet_id = ?""",
                Long.class, walletId)).as("보류 수익 HP 합계").isEqualTo(wallet.getHeldRevenueHp());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(u.remaining_held_hp), 0) FROM billing.point_charge_usage u
                JOIN billing.point_hold h ON h.id = u.point_hold_id WHERE h.wallet_id = ?""", Long.class, walletId))
                .as("배분 내역의 보류 합계 = 충전 HP 보류 잔액").isEqualTo(wallet.getHeldChargeHp());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_hold h
                WHERE h.wallet_id = ? AND h.original_charge_hp <> (
                    SELECT coalesce(sum(u.amount_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)""",
                Integer.class, walletId)).as("보류별 충전 HP = 충전 건별 배분 합계").isZero();
    }

    // 지갑의 HP 총량(가용 + 보류, 충전 + 수익)을 구한다.
    private long totalHp(Long walletId) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        return wallet.getChargeBalanceHp() + wallet.getRevenueBalanceHp() + wallet.getHeldChargeHp()
                + wallet.getHeldRevenueHp();
    }

    private long remainingPrincipal(Long chargeId) {
        return jdbcTemplate.queryForObject("SELECT remaining_principal_hp FROM billing.point_charge WHERE id = ?",
                Long.class, chargeId);
    }

    private int holdCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class);
    }

    private int usageCount(Long holdId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge_usage WHERE point_hold_id = ?",
                Integer.class, holdId);
    }

    // 작업 키의 원장을 "멱등키 HP종류 버킷 금액" 형식으로 ID 순서대로 읽는다.
    private List<String> ledgerLines(String operationKey) {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || point_type || ' ' || balance_bucket || ' ' || amount_hp
                FROM billing.point_ledger WHERE operation_key = ? ORDER BY id""", String.class, operationKey);
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
