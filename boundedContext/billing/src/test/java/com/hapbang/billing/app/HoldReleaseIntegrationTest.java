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
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeUsageRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// HP 보류 부분·전체 반환의 충전 HP 우선·충전 원금 역순 복원, 보류·상세·배분·충전 원금·원장·지갑 정합성,
// 반환 요청별 멱등성, 롤백, 동시성을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class HoldReleaseIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T00:00:00Z");
    private static final BillingReferenceType ORDER = BillingReferenceType.LECTURE_ORDER_ITEM;
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
    PointHoldRepository pointHoldRepository;

    @Autowired
    PointChargeUsageRepository pointChargeUsageRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 부분 반환
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전 HP만 보류한 보류를 부분 반환하면 나중에 배분한 충전 건부터 원금을 되돌린다")
    void partial_chargeOnly_lifo() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        HoldResult hold = hold(1L, 1001L, 5_000L);

        ReleaseResult result = release(1L, 1001L, "R1", 4_000L);

        assertThat(result.releasedHp()).isEqualTo(4_000L);
        assertThat(result.releasedChargeHp()).isEqualTo(4_000L);
        assertThat(result.releasedRevenueHp()).isZero();
        assertThat(result.chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 3_000L), new ChargeRestoration(first, 1_000L));
        assertThat(result.remainingHeldHp()).isEqualTo(1_000L);
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        assertThat(result.replayed()).isFalse();
        assertThat(remainingPrincipal(first)).isEqualTo(1_000L);
        assertThat(remainingPrincipal(second)).isEqualTo(3_000L);
        assertThat(usageRows(hold.holdId())).containsExactly(
                first + ":2000:1000:0:1000:PARTIALLY_PROCESSED", second + ":3000:0:0:3000:RELEASED");
        assertBalances(walletId, 4_000L, 0L, 1_000L, 0L);
        String key = releaseKey(hold, "R1");
        assertThat(ledgerLines(key)).containsExactly(
                key + ":1 CHARGE HELD -4000",
                key + ":2 CHARGE AVAILABLE 4000");
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT transaction_type || ' ' || reference_type || ' ' || reference_id
                FROM billing.point_ledger WHERE operation_key = ?""", String.class, key))
                .containsExactly("RELEASE LECTURE_ORDER_ITEM 1001");
        assertThat(detailRows(hold.holdId())).containsExactly(key + " RELEASE 4000 0 LECTURE_ORDER_ITEM 1001");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("수익 HP만 보류한 보류를 부분 반환하면 수익 HP만 되돌리고 충전 원금은 바꾸지 않는다")
    void partial_revenueOnly() {
        Long walletId = wallet(1L);
        revenue(walletId, 5_000L);
        Long chargeId = charge(walletId, 2_000L, T1);
        HoldResult hold = hold(1L, 1001L, 4_000L);

        ReleaseResult result = release(1L, 1001L, "R1", 1_500L);

        assertThat(result.releasedChargeHp()).isZero();
        assertThat(result.releasedRevenueHp()).isEqualTo(1_500L);
        assertThat(result.chargeRestorations()).isEmpty();
        assertThat(remainingPrincipal(chargeId)).isEqualTo(2_000L);
        assertBalances(walletId, 2_000L, 2_500L, 0L, 2_500L);
        String key = releaseKey(hold, "R1");
        assertThat(ledgerLines(key)).containsExactly(
                key + ":1 REVENUE HELD -1500",
                key + ":2 REVENUE AVAILABLE 1500");
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("수익·충전 혼합 보류는 충전 HP를 먼저 전부 반환하고 부족분만 수익 HP로 반환하며 HP 총량은 변하지 않는다")
    void partial_mixed_chargeFirst() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        Long first = charge(walletId, 4_000L, T1);
        Long second = charge(walletId, 6_000L, T2);
        HoldResult hold = hold(1L, 1001L, 8_000L);
        long totalBefore = totalHp(walletId);

        ReleaseResult result = release(1L, 1001L, "R1", 6_000L);

        assertThat(result.releasedChargeHp()).isEqualTo(5_000L);
        assertThat(result.releasedRevenueHp()).isEqualTo(1_000L);
        assertThat(result.chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 1_000L), new ChargeRestoration(first, 4_000L));
        assertBalances(walletId, 10_000L, 1_000L, 0L, 2_000L);
        assertThat(totalHp(walletId)).isEqualTo(totalBefore);
        // 원장은 충전 HP 이동 2줄, 수익 HP 이동 2줄을 같은 작업 키로 남긴다.
        String key = releaseKey(hold, "R1");
        assertThat(ledgerLines(key)).containsExactly(
                key + ":1 CHARGE HELD -5000",
                key + ":2 CHARGE AVAILABLE 5000",
                key + ":3 REVENUE HELD -1000",
                key + ":4 REVENUE AVAILABLE 1000");
        assertThat(holdRow(hold.holdId()))
                .containsEntry("held_charge_hp", 0L)
                .containsEntry("held_revenue_hp", 2_000L)
                .containsEntry("original_charge_hp", 5_000L)
                .containsEntry("original_revenue_hp", 3_000L)
                .containsEntry("status", "PARTIALLY_PROCESSED")
                .containsEntry("released_at", null);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("부분 반환을 여러 번 하면 남은 배분을 역순으로 이어서 반환하고, 각 반환의 충전 건별 복원을 재요청으로 다시 확인할 수 있다")
    void partial_multipleTimes() {
        Long walletId = wallet(1L);
        Long a = charge(walletId, 1_000L, T1);
        Long b = charge(walletId, 1_000L, T2);
        Long c = charge(walletId, 1_000L, T3);
        HoldResult hold = hold(1L, 1001L, 3_000L);

        ReleaseResult first = release(1L, 1001L, "R1", 500L);
        ReleaseResult second = release(1L, 1001L, "R2", 1_000L);
        ReleaseResult third = release(1L, 1001L, "R3", 1_000L);

        assertThat(first.chargeRestorations()).containsExactly(new ChargeRestoration(c, 500L));
        assertThat(second.chargeRestorations()).containsExactly(
                new ChargeRestoration(c, 500L), new ChargeRestoration(b, 500L));
        assertThat(third.chargeRestorations()).containsExactly(
                new ChargeRestoration(b, 500L), new ChargeRestoration(a, 500L));
        assertThat(third.remainingHeldHp()).isEqualTo(500L);
        assertThat(usageRows(hold.holdId())).containsExactly(
                a + ":1000:500:0:500:PARTIALLY_PROCESSED", b + ":1000:0:0:1000:RELEASED",
                c + ":1000:0:0:1000:RELEASED");
        assertThat(detailRows(hold.holdId())).hasSize(3);
        // 처리 건별 충전 건 복원은 상세를 순서대로 재생해 재구성한다(스키마 A안).
        assertThat(release(1L, 1001L, "R1", 500L).chargeRestorations()).isEqualTo(first.chargeRestorations());
        assertThat(release(1L, 1001L, "R2", 1_000L).chargeRestorations()).isEqualTo(second.chargeRestorations());
        assertThat(release(1L, 1001L, "R3", 1_000L).chargeRestorations()).isEqualTo(third.chargeRestorations());
        assertBalances(walletId, 2_500L, 0L, 500L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("남은 보류액보다 많이 반환하면 RELEASE_AMOUNT_EXCEEDS_HELD로 거절하고 아무것도 바꾸지 않는다")
    void partial_exceeds() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 5_000L, T1);
        hold(1L, 1001L, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R1", 3_001L), BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        assertThat(snapshot()).isEqualTo(before);

        release(1L, 1001L, "R2", 1_000L);
        Map<String, Object> afterPartial = snapshot();
        assertBillingError(() -> release(1L, 1001L, "R3", 2_001L), BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        assertThat(snapshot()).isEqualTo(afterPartial);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("반환으로 되돌린 충전 원금은 다음 보류에서 다시 FIFO(완료 시각 순)로 배분된다")
    void restoredPrincipal_reallocatedFifo() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        hold(1L, 1001L, 5_000L);
        release(1L, 1001L, "R1", 4_000L);

        HoldResult next = hold(1L, 1002L, 1_500L);

        assertThat(next.chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 1_000L), new ChargeAllocation(second, 500L));
        assertBalances(walletId, 2_500L, 0L, 2_500L, 0L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 전체 반환
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("최초 보류를 전체 반환하면 HP 종류·충전 건별로 보류 전과 같은 잔액으로 돌아가고 RELEASED로 끝난다")
    void full_initialHold() {
        Long walletId = wallet(1L);
        revenue(walletId, 3_000L);
        Long first = charge(walletId, 4_000L, T1);
        Long second = charge(walletId, 6_000L, T2);
        Map<String, Object> balancesBefore = balances(walletId);
        HoldResult hold = hold(1L, 1001L, 8_000L);

        ReleaseResult result = releaseFull(1L, 1001L, "R1");

        assertThat(result.releasedHp()).isEqualTo(8_000L);
        assertThat(result.releasedChargeHp()).isEqualTo(5_000L);
        assertThat(result.releasedRevenueHp()).isEqualTo(3_000L);
        assertThat(result.chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 1_000L), new ChargeRestoration(first, 4_000L));
        assertThat(result.remainingHeldHp()).isZero();
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.RELEASED);
        assertThat(balances(walletId)).isEqualTo(balancesBefore);
        assertThat(remainingPrincipal(first)).isEqualTo(4_000L);
        assertThat(remainingPrincipal(second)).isEqualTo(6_000L);
        assertThat(usageRows(hold.holdId())).containsExactly(
                first + ":4000:0:0:4000:RELEASED", second + ":1000:0:0:1000:RELEASED");
        assertThat(holdRow(hold.holdId())).containsEntry("status", "RELEASED");
        assertThat(holdRow(hold.holdId()).get("released_at")).isNotNull();
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("일부 반환 후 전체 반환하면 최초 보류액이 아니라 남은 보류액만 반환한다")
    void full_afterPartial() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        HoldResult hold = hold(1L, 1001L, 10_000L);
        release(1L, 1001L, "R1", 3_000L);

        ReleaseResult result = releaseFull(1L, 1001L, "R2");

        assertThat(result.releasedHp()).isEqualTo(7_000L);
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.RELEASED);
        assertThat(detailRows(hold.holdId())).containsExactly(
                releaseKey(hold, "R1") + " RELEASE 3000 0 LECTURE_ORDER_ITEM 1001",
                releaseKey(hold, "R2") + " RELEASE 7000 0 LECTURE_ORDER_ITEM 1001");
        assertBalances(walletId, 10_000L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("전체 반환 요청을 다시 보내면 남은 보류를 다시 계산하지 않고 처음 반환한 금액을 그대로 돌려준다")
    void full_retry_keepsFirstResult() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 10_000L);
        release(1L, 1001L, "R1", 3_000L);
        ReleaseResult first = releaseFull(1L, 1001L, "R2");
        Map<String, Object> before = snapshot();

        ReleaseResult retried = releaseFull(1L, 1001L, "R2");

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("보류가 끝난 뒤에도 이미 처리한 반환 요청의 재요청은 그때의 결과를 돌려준다")
    void retryAfterClosed_replays() {
        Long walletId = wallet(1L);
        Long chargeId = charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 10_000L);
        ReleaseResult partial = release(1L, 1001L, "R1", 3_000L);
        releaseFull(1L, 1001L, "R2");

        ReleaseResult retried = release(1L, 1001L, "R1", 3_000L);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.releasedHp()).isEqualTo(3_000L);
        assertThat(retried.chargeRestorations()).isEqualTo(partial.chargeRestorations())
                .containsExactly(new ChargeRestoration(chargeId, 3_000L));
        assertThat(retried.remainingHeldHp()).isZero();
        assertThat(retried.holdStatus()).isEqualTo(PointHoldStatus.RELEASED);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("이미 끝난 보류에 새 반환 요청이 오면 부분·전체 모두 HOLD_ALREADY_CLOSED로 거절하고 아무것도 바꾸지 않는다")
    void closedHold_newRequestRejected() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 3_000L);
        releaseFull(1L, 1001L, "R1");
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R2", 1L), BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertBillingError(() -> releaseFull(1L, 1001L, "R3"), BillingErrorCode.HOLD_ALREADY_CLOSED);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("이미 지급한 금액은 반환할 수 없고, 지급 이력이 있는 보류의 나머지를 모두 반환하면 MIXED로 끝난다")
    void paidAmount_notReleasable() {
        Long walletId = wallet(1L);
        Long receiverWalletId = wallet(2L);
        revenue(walletId, 3_000L);
        Long chargeId = charge(walletId, 2_000L, T1);
        HoldResult hold = hold(1L, 1001L, 5_000L);
        // 지급 기능은 아직 없으므로 수익 HP 3,000을 받는 쪽에 지급한 상태를 직접 만든다.
        payoutRevenue(hold, walletId, receiverWalletId, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R1", 2_001L), BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
        assertThat(snapshot()).isEqualTo(before);

        ReleaseResult result = releaseFull(1L, 1001L, "R2");

        assertThat(result.releasedHp()).isEqualTo(2_000L);
        assertThat(result.releasedRevenueHp()).isZero();
        assertThat(result.chargeRestorations()).containsExactly(new ChargeRestoration(chargeId, 2_000L));
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertBalances(walletId, 2_000L, 0L, 0L, 0L);
        assertBalances(receiverWalletId, 0L, 3_000L, 0L, 0L);
        assertBillingError(() -> release(1L, 1001L, "R3", 1L), BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertInvariants(walletId);
        assertInvariants(receiverWalletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 반환 요청을 다시 보내면 보류·상세·배분·충전 원금·원장·지갑을 바꾸지 않고 처음 결과를 돌려준다")
    void sameRequest_sequentialRetry() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 3_000L, T1);
        charge(walletId, 3_000L, T2);
        hold(1L, 1001L, 5_000L);
        ReleaseResult first = release(1L, 1001L, "R1", 4_500L);
        Map<String, Object> before = snapshot();

        ReleaseResult retried = release(1L, 1001L, "R1", 4_500L);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 반환 요청이 동시에 여러 번 들어와도 한 번만 반환하고 나머지는 같은 결과를 돌려받는다")
    void sameRequest_concurrent() throws Exception {
        Long walletId = wallet(1L);
        charge(walletId, 3_000L, T1);
        charge(walletId, 3_000L, T2);
        HoldResult hold = hold(1L, 1001L, 5_000L);
        List<Callable<ReleaseResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> release(1L, 1001L, "R1", 4_000L));
        }

        List<ReleaseResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(ReleaseResult::holdDetailId).containsOnly(results.getFirst().holdDetailId());
        assertThat(detailRows(hold.holdId())).hasSize(1);
        assertThat(ledgerLines(releaseKey(hold, "R1"))).hasSize(2);
        assertBalances(walletId, 5_000L, 0L, 1_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 반환 요청 ID로 다른 금액을 요청하면 IDEMPOTENCY_KEY_CONFLICT로 거절하고 아무것도 바꾸지 않는다")
    void sameRequestId_differentAmount_conflict() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 5_000L);
        release(1L, 1001L, "R1", 1_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R1", 2_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("반환 요청 ID는 보류 안에서 고유하므로, 다른 보류(같은 지갑·다른 지갑)의 같은 요청 ID는 별개 요청으로 각각 반환한다")
    void sameRequestId_differentHold_separateRequests() {
        Long walletId = wallet(1L);
        Long otherWalletId = wallet(2L);
        charge(walletId, 10_000L, T1);
        charge(otherWalletId, 10_000L, T1);
        HoldResult first = hold(1L, 1001L, 3_000L);
        HoldResult second = hold(1L, 1002L, 3_000L);
        HoldResult other = hold(2L, 2001L, 3_000L);

        ReleaseResult firstRelease = release(1L, 1001L, "R1", 1_000L);
        ReleaseResult secondRelease = release(1L, 1002L, "R1", 2_000L);
        ReleaseResult otherRelease = releaseFull(2L, 2001L, "R1");

        assertThat(List.of(firstRelease, secondRelease, otherRelease)).noneMatch(ReleaseResult::replayed);
        assertThat(detailRows(first.holdId())).containsExactly(releaseKey(first, "R1") + " RELEASE 1000 0 LECTURE_ORDER_ITEM 1001");
        assertThat(detailRows(second.holdId())).containsExactly(releaseKey(second, "R1") + " RELEASE 2000 0 LECTURE_ORDER_ITEM 1002");
        assertThat(detailRows(other.holdId())).containsExactly(releaseKey(other, "R1") + " RELEASE 3000 0 LECTURE_ORDER_ITEM 2001");
        // 재요청은 각 보류의 처음 결과로 돌아간다.
        assertThat(release(1L, 1002L, "R1", 2_000L).replayed()).isTrue();
        assertBillingError(() -> release(1L, 1001L, "R1", 2_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBalances(walletId, 7_000L, 0L, 3_000L, 0L);
        assertBalances(otherWalletId, 10_000L, 0L, 0L, 0L);
        assertInvariants(walletId);
        assertInvariants(otherWalletId);
    }

    @Test
    @DisplayName("서로 다른 반환 요청은 남은 보류액 안에서 각각 반환한다")
    void differentRequests_accumulate() {
        Long walletId = wallet(1L);
        revenue(walletId, 2_000L);
        charge(walletId, 3_000L, T1);
        HoldResult hold = hold(1L, 1001L, 5_000L);

        ReleaseResult first = release(1L, 1001L, "R1", 2_000L);
        ReleaseResult second = release(1L, 1001L, "R2", 2_000L);

        assertThat(first.releasedChargeHp()).isEqualTo(2_000L);
        assertThat(second.releasedChargeHp()).isEqualTo(1_000L);
        assertThat(second.releasedRevenueHp()).isEqualTo(1_000L);
        assertThat(second.remainingHeldHp()).isEqualTo(1_000L);
        assertThat(detailRows(hold.holdId())).hasSize(2);
        assertBalances(walletId, 3_000L, 1_000L, 0L, 1_000L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("반환이 실패해 롤백된 뒤 같은 요청을 다시 보내면 처음 처리하는 요청으로 한 번만 반환한다")
    void failedThenRetried() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        HoldResult hold = hold(1L, 1001L, 5_000L);

        failOn("point_hold_detail", "INSERT",
                () -> assertThatThrownBy(() -> release(1L, 1001L, "R1", 2_000L))
                        .hasStackTraceContaining("test failure"));
        ReleaseResult retried = release(1L, 1001L, "R1", 2_000L);

        assertThat(retried.replayed()).isFalse();
        assertThat(retried.releasedHp()).isEqualTo(2_000L);
        assertThat(detailRows(hold.holdId())).hasSize(1);
        assertBalances(walletId, 7_000L, 0L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 보류에 서로 다른 반환이 동시에 들어와도 남은 보류액을 넘지 않는 만큼만 성공한다")
    void differentRequests_concurrent_noOverRelease() throws Exception {
        Long walletId = wallet(1L);
        List<Long> chargeIds = List.of(charge(walletId, 1_000L, T1), charge(walletId, 1_000L, T2),
                charge(walletId, 1_000L, T3));
        HoldResult hold = hold(1L, 1001L, 3_000L);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String requestId = "R" + i;
            tasks.add(() -> {
                try {
                    release(1L, 1001L, requestId, 700L);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.RELEASE_AMOUNT_EXCEEDS_HELD);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(4);
        assertThat(detailRows(hold.holdId())).hasSize(4);
        assertThat(chargeIds.stream().mapToLong(this::remainingPrincipal).sum()).isEqualTo(2_800L);
        assertBalances(walletId, 2_800L, 0L, 200L, 0L);
        // 동시에 커밋된 반환도 상세 ID 순서가 처리 순서와 같아 재생 대조를 통과하고, 남은 보류는 가장 먼저 배분한 충전 건에 있다.
        assertThat(releaseFull(1L, 1001L, "FINAL").chargeRestorations())
                .containsExactly(new ChargeRestoration(chargeIds.getFirst(), 200L));
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 요청 검증과 비정상 상태
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("보류가 없으면 HOLD_NOT_FOUND, 결제 회원의 지갑이 없으면 WALLET_NOT_FOUND로 거절한다")
    void notFound() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);

        assertBillingError(() -> release(1L, 1001L, "R1", 1_000L), BillingErrorCode.HOLD_NOT_FOUND);
        assertBillingError(() -> release(9L, 1001L, "R1", 1_000L), BillingErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원의 보류를 반환하려 하면 HOLD_OWNER_MISMATCH로 거절하고 아무것도 바꾸지 않는다")
    void otherMembersHold() {
        Long walletId = wallet(1L);
        wallet(2L);
        charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(2L, 1001L, "R1", 1_000L), BillingErrorCode.HOLD_OWNER_MISMATCH);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("반환 상세 없이 같은 작업 키의 원장만 있으면 정상 재요청으로 보지 않고 HOLD_STATE_INCONSISTENT로 거절한다")
    void ledgerWithoutDetail_inconsistent() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        HoldResult hold = hold(1L, 1001L, 3_000L);
        insertLedgerRow(walletId, releaseKey(hold, "R1"), releaseKey(hold, "R1") + ":1");
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R1", 1_000L), BillingErrorCode.HOLD_STATE_INCONSISTENT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("배분의 누적값이 반환 상세의 재생 결과와 다르면 처리 이력을 재구성할 수 없으므로 HOLD_STATE_INCONSISTENT로 거절한다")
    void usageNotMatchingHistory_inconsistent() {
        Long walletId = wallet(1L);
        charge(walletId, 1_000L, T1);
        charge(walletId, 1_000L, T2);
        HoldResult hold = hold(1L, 1001L, 2_000L);
        release(1L, 1001L, "R1", 500L);
        // 역순 규칙이면 두 번째 배분에서 반환했어야 하는데 첫 번째 배분에서 반환한 것처럼 바꾼다(합계는 같다).
        jdbcTemplate.update("""
                UPDATE billing.point_charge_usage
                SET remaining_held_hp = remaining_held_hp + CASE WHEN id = ? THEN -500 ELSE 500 END,
                    released_hp = released_hp + CASE WHEN id = ? THEN 500 ELSE -500 END
                WHERE point_hold_id = ?""", firstUsageId(hold), firstUsageId(hold), hold.holdId());
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R2", 500L), BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> release(1L, 1001L, "R1", 500L), BillingErrorCode.HOLD_STATE_INCONSISTENT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("충전 원금 합계가 지갑 충전 HP 잔액과 다르면 CHARGE_PRINCIPAL_MISMATCH로 거절하고 아무것도 바꾸지 않는다")
    void chargePrincipalMismatch() {
        Long walletId = wallet(1L);
        Long otherWalletId = wallet(2L);
        charge(walletId, 5_000L, T1);
        revenue(otherWalletId, 2_000L);
        charge(otherWalletId, 5_000L, T1);
        hold(1L, 1001L, 4_000L);
        hold(2L, 2001L, 2_000L);
        // 충전 건 없이 충전 원장만 기록해 지갑 충전 HP와 충전 원금 합계를 어긋나게 만든다.
        for (Long target : List.of(walletId, otherWalletId)) {
            ledgerRecordUseCase.record(new LedgerOperation("CHARGE:ORPHAN:" + target, LedgerTransactionType.CHARGE,
                    BillingReferenceType.POINT_CHARGE, 999L, null, T1,
                    List.of(LedgerEntry.of(target, PointType.CHARGE, BalanceBucket.AVAILABLE, 1_000L))));
        }
        Map<String, Object> before = snapshot();

        assertBillingError(() -> release(1L, 1001L, "R1", 3_000L), BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);
        // 수익 HP만 반환하는 경우도 불일치 상태에서는 진행하지 않는다.
        assertBillingError(() -> releaseFull(2L, 2001L, "R2"), BillingErrorCode.CHARGE_PRINCIPAL_MISMATCH);

        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // 정합성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("한 보류를 반환해도 같은 지갑의 다른 보류와 그 충전 원금 배분은 바뀌지 않는다")
    void otherHoldIsolated() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 2_000L, T2);
        HoldResult a = hold(1L, 1001L, 3_000L);
        HoldResult b = hold(1L, 1002L, 1_000L);
        List<String> usagesOfB = usageRows(b.holdId());
        Map<String, Object> holdB = holdRow(b.holdId());

        ReleaseResult result = releaseFull(1L, 1001L, "R1");

        assertThat(result.chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 1_000L), new ChargeRestoration(first, 2_000L));
        assertThat(usageRows(b.holdId())).isEqualTo(usagesOfB).containsExactly(second + ":1000:1000:0:0:HELD");
        assertThat(holdRow(b.holdId())).isEqualTo(holdB);
        assertThat(remainingPrincipal(first)).isEqualTo(2_000L);
        assertThat(remainingPrincipal(second)).isEqualTo(1_000L);
        assertThat(a.holdId()).isNotEqualTo(b.holdId());
        assertBalances(walletId, 3_000L, 0L, 1_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("한 트랜잭션에서 보류·부분 반환·전체 반환·다음 보류를 이어서 해도 미반영 변경을 잃지 않는다")
    void sequenceInSameTransaction() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        List<Object> results = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            results.add(hold(1L, 1001L, 4_000L));
            results.add(release(1L, 1001L, "R1", 1_000L));
            results.add(releaseFull(1L, 1001L, "R2"));
            results.add(hold(1L, 1002L, 2_500L));
            results.add(release(1L, 1001L, "R1", 1_000L));
        });

        assertThat(((ReleaseResult) results.get(1)).chargeRestorations())
                .containsExactly(new ChargeRestoration(second, 1_000L));
        assertThat(((ReleaseResult) results.get(2)).chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 1_000L), new ChargeRestoration(first, 2_000L));
        assertThat(((HoldResult) results.get(3)).chargeAllocations()).containsExactly(
                new ChargeAllocation(first, 2_000L), new ChargeAllocation(second, 500L));
        assertThat(((ReleaseResult) results.get(4)).replayed()).isTrue();
        assertBalances(walletId, 2_500L, 0L, 2_500L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션이 미리 읽어 둔 보류·배분·충전 건이 있어도 락을 잡은 뒤 최신 상태로 다시 읽어 반환한다")
    void staleEntitiesInCallerTransaction_refreshed() {
        Long walletId = wallet(1L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        HoldResult hold = hold(1L, 1001L, 5_000L);
        List<ReleaseResult> results = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 락 없이 먼저 읽어 영속성 컨텍스트에 올려 둔다.
            pointHoldRepository.findById(hold.holdId()).orElseThrow();
            pointChargeUsageRepository.findAllByPointHoldIdOrderByIdAsc(hold.holdId())
                    .forEach(usage -> usage.getCharge().getRemainingPrincipalHp());
            // 그사이 다른 트랜잭션이 같은 보류를 반환하고 커밋한다.
            runInOtherThread(() -> release(1L, 1001L, "R1", 2_000L));
            results.add(release(1L, 1001L, "R2", 2_000L));
        });

        assertThat(results.getFirst().chargeRestorations()).containsExactly(
                new ChargeRestoration(second, 1_000L), new ChargeRestoration(first, 1_000L));
        assertThat(results.getFirst().remainingHeldHp()).isEqualTo(1_000L);
        assertThat(usageRows(hold.holdId())).containsExactly(
                first + ":2000:1000:0:1000:PARTIALLY_PROCESSED", second + ":3000:0:0:3000:RELEASED");
        assertBalances(walletId, 4_000L, 0L, 1_000L, 0L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 트랜잭션
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("원장 저장이 실패하면 보류·상세·배분·충전 원금·지갑 변경이 모두 롤백된다")
    void ledgerFailure_rollsBackAll() {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 10_000L, T1);
        HoldResult hold = hold(1L, 1001L, 5_000L);
        // 다른 작업이 이 반환의 3번째 원장 멱등키를 이미 쓰고 있어 수익 HP 이동 INSERT가 UNIQUE 충돌로 실패한다.
        insertLedgerRow(walletId, "OTHER", releaseKey(hold, "R1") + ":3");
        Map<String, Object> before = snapshot();

        assertBillingError(() -> releaseFull(1L, 1001L, "R1"), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("반환 상세 저장이 실패하면 아무것도 바뀌지 않는다")
    void detailInsertFailure_rollsBackAll() {
        assertRollbackOn("point_hold_detail", "INSERT");
    }

    @Test
    @DisplayName("충전 원금 복원이 실패하면 아무것도 바뀌지 않는다")
    void chargeUpdateFailure_rollsBackAll() {
        assertRollbackOn("point_charge", "UPDATE");
    }

    @Test
    @DisplayName("충전 원금 배분 변경이 실패하면 아무것도 바뀌지 않는다")
    void usageUpdateFailure_rollsBackAll() {
        assertRollbackOn("point_charge_usage", "UPDATE");
    }

    @Test
    @DisplayName("보류 변경이 실패하면 아무것도 바뀌지 않는다")
    void holdUpdateFailure_rollsBackAll() {
        assertRollbackOn("point_hold", "UPDATE");
    }

    @Test
    @DisplayName("원장 INSERT가 실패하면 아무것도 바뀌지 않는다")
    void ledgerInsertFailure_rollsBackAll() {
        assertRollbackOn("point_ledger", "INSERT");
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션에 참여하며, 그 트랜잭션이 롤백되면 반환도 모두 롤백된다")
    void joinsCallerTransaction() {
        Long walletId = wallet(1L);
        charge(walletId, 10_000L, T1);
        hold(1L, 1001L, 5_000L);
        Map<String, Object> before = snapshot();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            release(1L, 1001L, "R1", 2_000L);
            throw new IllegalStateException("이후 업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(release(1L, 1001L, "R1", 2_000L).replayed()).isFalse();
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private HoldResult hold(Long userId, Long referenceId, long amountHp) {
        return holdFacade.hold(new HoldCommand(userId, ORDER, referenceId, amountHp));
    }

    private ReleaseResult release(Long userId, Long referenceId, String requestId, long amountHp) {
        return holdFacade.release(ReleaseCommand.partial(userId, ORDER, referenceId, requestId, amountHp));
    }

    private ReleaseResult releaseFull(Long userId, Long referenceId, String requestId) {
        return holdFacade.release(ReleaseCommand.full(userId, ORDER, referenceId, requestId));
    }

    private static String releaseKey(HoldResult hold, String requestId) {
        return "RELEASE:" + hold.holdId() + ":" + requestId;
    }

    // 수익·충전 혼합 보류를 만든 뒤 테이블에 행 실패 트리거를 걸고 전체 반환이 실패하면 아무것도 바뀌지 않는지 확인한다.
    private void assertRollbackOn(String table, String event) {
        Long walletId = wallet(1L);
        revenue(walletId, 1_000L);
        charge(walletId, 2_000L, T1);
        charge(walletId, 3_000L, T2);
        hold(1L, 1001L, 5_000L);
        Map<String, Object> before = snapshot();

        failOn(table, event, () -> assertThatThrownBy(() -> releaseFull(1L, 1001L, "R1"))
                .hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(walletId);
    }

    // 지급 기능 대신, 보류의 수익 HP 일부를 받는 쪽 지갑에 지급한 상태(지급 상세·보류 감소·원장)를 만든다.
    private void payoutRevenue(HoldResult hold, Long payerWalletId, Long receiverWalletId, long amountHp) {
        String key = "PAYOUT:" + hold.holdId() + ":P1";
        jdbcTemplate.update("""
                INSERT INTO billing.point_hold_detail (point_hold_id, reference_type, reference_id, action_type,
                    charge_amount_hp, revenue_amount_hp, idempotency_key, created_at)
                VALUES (?, 'LECTURE_ORDER_ITEM', ?, 'PAYOUT', 0, ?, ?, now())""",
                hold.holdId(), hold.referenceId(), amountHp, key);
        jdbcTemplate.update("""
                UPDATE billing.point_hold SET held_revenue_hp = held_revenue_hp - ?, status = 'PARTIALLY_PROCESSED'
                WHERE id = ?""", amountHp, hold.holdId());
        ledgerRecordUseCase.record(new LedgerOperation(key, LedgerTransactionType.PAYOUT, ORDER, hold.referenceId(),
                null, T3, List.of(
                LedgerEntry.of(payerWalletId, PointType.REVENUE, BalanceBucket.HELD, -amountHp),
                LedgerEntry.of(receiverWalletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    // 회원 ID로 잔액이 0인 지갑을 저장하고 지갑 ID를 반환한다.
    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다.
    private Long charge(Long walletId, long amountHp, Instant completedAt) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, ?, ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + sequence.incrementAndGet(),
                PointChargeStatus.COMPLETED.name(), Timestamp.from(T1), Timestamp.from(completedAt));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, completedAt,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
        return chargeId;
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

    // 지갑 잔액·충전 원금·보류·상세·배분·원장 상태를 비교할 수 있게 모은다.
    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("charges", jdbcTemplate.queryForList(
                "SELECT id, remaining_principal_hp FROM billing.point_charge ORDER BY id"));
        snapshot.put("holds", jdbcTemplate.queryForList("""
                SELECT id, held_charge_hp, held_revenue_hp, status, released_at, updated_at
                FROM billing.point_hold ORDER BY id"""));
        snapshot.put("details", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, charge_amount_hp, revenue_amount_hp FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("usages", jdbcTemplate.queryForList("""
                SELECT id, remaining_held_hp, consumed_hp, released_hp, status, updated_at
                FROM billing.point_charge_usage ORDER BY id"""));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    private Map<String, Object> balances(Long walletId) {
        return jdbcTemplate.queryForMap("""
                SELECT charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet WHERE id = ?""", walletId);
    }

    // 지갑의 네 가지 캐시 잔액을 DB에서 다시 읽어 확인한다.
    private void assertBalances(Long walletId, long charge, long revenue, long heldCharge, long heldRevenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).as("chargeBalanceHp").isEqualTo(charge);
        assertThat(wallet.getRevenueBalanceHp()).as("revenueBalanceHp").isEqualTo(revenue);
        assertThat(wallet.getHeldChargeHp()).as("heldChargeHp").isEqualTo(heldCharge);
        assertThat(wallet.getHeldRevenueHp()).as("heldRevenueHp").isEqualTo(heldRevenue);
    }

    // 반환 정합성 불변식: 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류 합계 = 보류 잔액,
    // 보류별 충전 HP = 배분 합계, 배분의 남은 보류 합계 = 충전 HP 보류 잔액, 보류별 처리액 = 상세 합계.
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
                WHERE h.wallet_id = ? AND (h.original_charge_hp <> (
                        SELECT coalesce(sum(u.amount_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)
                    OR h.original_charge_hp - h.held_charge_hp <> (
                        SELECT coalesce(sum(d.charge_amount_hp), 0) FROM billing.point_hold_detail d WHERE d.point_hold_id = h.id)
                    OR h.original_revenue_hp - h.held_revenue_hp <> (
                        SELECT coalesce(sum(d.revenue_amount_hp), 0) FROM billing.point_hold_detail d WHERE d.point_hold_id = h.id)
                    OR h.original_charge_hp - h.held_charge_hp <> (
                        SELECT coalesce(sum(u.consumed_hp + u.released_hp), 0) FROM billing.point_charge_usage u
                        WHERE u.point_hold_id = h.id))""",
                Integer.class, walletId)).as("보류별 배분·상세·처리액 일치").isZero();
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

    private Map<String, Object> holdRow(Long holdId) {
        return jdbcTemplate.queryForMap("""
                SELECT original_charge_hp, original_revenue_hp, held_charge_hp, held_revenue_hp, status, released_at
                FROM billing.point_hold WHERE id = ?""", holdId);
    }

    private Long firstUsageId(HoldResult hold) {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM billing.point_charge_usage WHERE point_hold_id = ?", Long.class, hold.holdId());
    }

    // 보류의 배분을 "충전ID:배분액:남은보류:소진:반환:상태" 형식으로 배분 순서대로 읽는다.
    private List<String> usageRows(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT charge_id || ':' || amount_hp || ':' || remaining_held_hp || ':' || consumed_hp || ':'
                    || released_hp || ':' || status
                FROM billing.point_charge_usage WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    // 보류의 상세를 "멱등키 처리유형 충전액 수익액 참조유형 참조ID" 형식으로 ID 순서대로 읽는다.
    private List<String> detailRows(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || action_type || ' ' || charge_amount_hp || ' ' || revenue_amount_hp
                    || ' ' || reference_type || ' ' || reference_id
                FROM billing.point_hold_detail WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
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

    // 다른 스레드(별도 트랜잭션)에서 작업을 실행하고 끝날 때까지 기다린다.
    private void runInOtherThread(Runnable action) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(action).get(60, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("다른 트랜잭션의 작업이 실패했습니다.", exception);
        } finally {
            executor.shutdownNow();
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
