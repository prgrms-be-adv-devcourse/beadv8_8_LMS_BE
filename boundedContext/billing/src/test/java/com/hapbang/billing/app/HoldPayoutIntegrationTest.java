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

import com.hapbang.billing.app.PayoutResult.ChargeConsumption;
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingReferenceType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.SettlementSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 보류 지급(수익 HP 우선·충전 원금 FIFO 소진)·반환과의 혼합 이력·위약금 정산(70% 반환 + 30% 지급)의 금융 정합성,
// 멱등성, 롤백, 다중 지갑 동시성을 실제 PostgreSQL로 검증한다.
@IntegrationTest
class HoldPayoutIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T00:00:00Z");
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final BillingReferenceType RECRUIT = BillingReferenceType.APPEARANCE_RECRUIT;
    private static final SettlementSourceType REQUEST_SOURCE = SettlementSourceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType RECRUIT_SOURCE = SettlementSourceType.RECRUIT_APPEARANCE;
    private static final long HOST = 1L;
    private static final long GUEST = 2L;
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
    // 정상 지급
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("수익 HP만 보류한 출연 요청을 전체 지급하면 게스트 수익 HP로 옮기고 보류는 PAID로 끝나며 지급 건·상세·원장이 남는다")
    void payout_revenueOnly_full() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 5_000L);
        HoldResult hold = hold(HOST, REQUEST, 7L, 4_000L);

        PayoutResult result = holdFacade.payout(PayoutCommand.full(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L));

        assertThat(result.paidHp()).isEqualTo(4_000L);
        assertThat(result.paidRevenueHp()).isEqualTo(4_000L);
        assertThat(result.paidChargeHp()).isZero();
        assertThat(result.chargeConsumptions()).isEmpty();
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.PAID);
        assertThat(result.remainingHeldHp()).isZero();
        assertThat(result.payerWalletId()).isEqualTo(host);
        assertThat(result.receiverWalletId()).isEqualTo(guest);
        assertBalances(host, 0L, 1_000L, 0L, 0L);
        assertBalances(guest, 0L, 4_000L, 0L, 0L);
        String key = "PAYOUT:" + hold.holdId() + ":P1";
        assertThat(ledgerLines(key)).containsExactly(
                host + " " + key + ":1 REVENUE HELD -4000",
                guest + " " + key + ":2 REVENUE AVAILABLE 4000");
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT transaction_type || ' ' || reference_type || ' ' || reference_id
                FROM billing.point_ledger WHERE operation_key = ?""", String.class, key))
                .containsExactly("PAYOUT APPEARANCE_REQUEST 7");
        assertThat(detailRows(hold.holdId())).containsExactly(key + " PAYOUT 0 4000");
        assertThat(settlementRows(hold.holdId())).containsExactly(
                key + " " + GUEST + " " + guest + " APPEARANCE_REQUEST 7 NORMAL 4000 4000 COMPLETED");
        assertThat(holdRow(hold.holdId())).containsEntry("status", "PAID").containsEntry("released_at", null);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("충전 HP만 보류한 보류를 부분 지급하면 먼저 배분한 충전 건부터 소진하고, 소진한 원금은 호스트에게 돌아가지 않는다")
    void payout_chargeOnly_fifo() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        Long first = charge(host, 2_000L, T1);
        Long second = charge(host, 3_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 5_000L);

        PayoutResult result = payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 2_500L);

        assertThat(result.paidChargeHp()).isEqualTo(2_500L);
        assertThat(result.chargeConsumptions()).containsExactly(
                new ChargeConsumption(first, 2_000L), new ChargeConsumption(second, 500L));
        assertThat(usageRows(hold.holdId())).containsExactly(
                first + ":2000:0:2000:0:CONSUMED", second + ":3000:2500:500:0:PARTIALLY_PROCESSED");
        assertThat(remainingPrincipal(first)).isZero();
        assertThat(remainingPrincipal(second)).isZero();
        assertBalances(host, 0L, 0L, 2_500L, 0L);
        // 출처가 충전 HP여도 받는 쪽에는 수익 HP로 들어간다.
        assertBalances(guest, 0L, 2_500L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("수익·충전 혼합 보류는 수익 HP 보류를 먼저 지급하고 부족분만 충전 HP 보류에서 지급하며, 호스트 감소 = 게스트 증가다")
    void payout_mixed_revenueFirst() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 3_000L);
        Long first = charge(host, 4_000L, T1);
        charge(host, 6_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 8_000L);

        PayoutResult result = payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 4_000L);

        assertThat(result.paidRevenueHp()).isEqualTo(3_000L);
        assertThat(result.paidChargeHp()).isEqualTo(1_000L);
        assertThat(result.chargeConsumptions()).containsExactly(new ChargeConsumption(first, 1_000L));
        String key = "PAYOUT:" + hold.holdId() + ":P1";
        assertThat(ledgerLines(key)).containsExactly(
                host + " " + key + ":1 REVENUE HELD -3000",
                host + " " + key + ":2 CHARGE HELD -1000",
                guest + " " + key + ":3 REVENUE AVAILABLE 4000");
        assertBalances(host, 5_000L, 0L, 4_000L, 0L);
        assertBalances(guest, 0L, 4_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("출연 모집 보류에서 게스트마다 각자 지급하고 남은 금액을 호스트에게 반환하면 MIXED로 끝난다")
    void payout_recruit_multipleGuests() {
        Long host = wallet(HOST);
        Long guestA = wallet(11L);
        Long guestB = wallet(12L);
        charge(host, 30_000L, T1);
        charge(host, 30_000L, T2);
        HoldResult hold = hold(HOST, RECRUIT, 9L, 60_000L);

        PayoutResult a = payout(HOST, RECRUIT, 9L, "A-101", 11L, RECRUIT_SOURCE, 101L, 20_000L);
        PayoutResult b = payout(HOST, RECRUIT, 9L, "B-102", 12L, RECRUIT_SOURCE, 102L, 25_000L);
        ReleaseResult rest = holdFacade.release(ReleaseCommand.full(HOST, RECRUIT, 9L, "UNDERFILLED"));

        assertThat(a.remainingHeldHp()).isEqualTo(40_000L);
        assertThat(b.remainingHeldHp()).isEqualTo(15_000L);
        assertThat(rest.releasedHp()).isEqualTo(15_000L);
        assertThat(rest.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(settlementRows(hold.holdId())).hasSize(2);
        assertBalances(host, 15_000L, 0L, 0L, 0L);
        assertBalances(guestA, 0L, 20_000L, 0L, 0L);
        assertBalances(guestB, 0L, 25_000L, 0L, 0L);
        assertInvariants(host, guestA, guestB);
    }

    @Test
    @DisplayName("남은 보류보다 많은 지급은 PAYOUT_AMOUNT_EXCEEDS_HELD로 거절하고 아무것도 바꾸지 않는다")
    void payout_exceeds() {
        Long host = wallet(HOST);
        wallet(GUEST);
        revenue(host, 1_000L);
        charge(host, 5_000L, T1);
        hold(HOST, REQUEST, 7L, 3_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 3_001L),
                BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("받는 지갑 없음·자기 자신·보류 없음·다른 회원의 보류·지급 원천 불일치·같은 원천 재지급·끝난 보류는 거절하고 아무것도 바꾸지 않는다")
    void payout_invalidRequests() {
        Long host = wallet(HOST);
        wallet(GUEST);
        wallet(3L);
        charge(host, 10_000L, T1);
        hold(HOST, REQUEST, 7L, 3_000L);
        hold(HOST, RECRUIT, 9L, 3_000L);
        payout(HOST, RECRUIT, 9L, "A-1", GUEST, RECRUIT_SOURCE, 101L, 1_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", 99L, REQUEST_SOURCE, 7L, 1_000L),
                BillingErrorCode.WALLET_NOT_FOUND);
        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", HOST, REQUEST_SOURCE, 7L, 1_000L),
                BillingErrorCode.PAYOUT_TO_PAYER);
        assertBillingError(() -> payout(HOST, REQUEST, 8L, "P1", GUEST, REQUEST_SOURCE, 8L, 1_000L),
                BillingErrorCode.HOLD_NOT_FOUND);
        assertBillingError(() -> payout(3L, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 1_000L),
                BillingErrorCode.HOLD_OWNER_MISMATCH);
        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", GUEST, RECRUIT_SOURCE, 7L, 1_000L),
                BillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "P1", GUEST, REQUEST_SOURCE, 9L, 1_000L),
                BillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        // 같은 게스트 출연에 다른 지급 요청 ID로 두 번 지급하지 않는다.
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "A-2", 3L, RECRUIT_SOURCE, 101L, 1_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertThat(snapshot()).isEqualTo(before);

        holdFacade.release(ReleaseCommand.full(HOST, REQUEST, 7L, "R1"));
        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 1L),
                BillingErrorCode.HOLD_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("같은 지급 요청을 다시 보내면 바꾸지 않고 처음 결과를 돌려주며, 같은 ID로 다른 금액·받는 회원·원천이면 충돌이다")
    void payout_idempotency() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        wallet(3L);
        charge(host, 3_000L, T1);
        charge(host, 3_000L, T2);
        hold(HOST, RECRUIT, 9L, 6_000L);
        PayoutResult first = payout(HOST, RECRUIT, 9L, "A-101", GUEST, RECRUIT_SOURCE, 101L, 4_000L);
        Map<String, Object> before = snapshot();

        PayoutResult retried = payout(HOST, RECRUIT, 9L, "A-101", GUEST, RECRUIT_SOURCE, 101L, 4_000L);
        PayoutResult retriedFull = holdFacade.payout(
                PayoutCommand.full(HOST, RECRUIT, 9L, "A-101", GUEST, RECRUIT_SOURCE, 101L));

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(retriedFull.paidHp()).isEqualTo(4_000L);
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "A-101", GUEST, RECRUIT_SOURCE, 101L, 3_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "A-101", 3L, RECRUIT_SOURCE, 101L, 4_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "A-101", GUEST, RECRUIT_SOURCE, 102L, 4_000L),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("지급 요청 ID는 보류 안에서 고유하므로, 다른 보류의 같은 지급 요청 ID는 별개 지급이다")
    void payout_sameRequestIdOtherHold() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 10_000L, T1);
        hold(HOST, REQUEST, 7L, 3_000L);
        hold(HOST, REQUEST, 8L, 2_000L);

        PayoutResult first = payout(HOST, REQUEST, 7L, "CONFIRMED", GUEST, REQUEST_SOURCE, 7L, 3_000L);
        PayoutResult second = payout(HOST, REQUEST, 8L, "CONFIRMED", GUEST, REQUEST_SOURCE, 8L, 2_000L);

        assertThat(List.of(first.replayed(), second.replayed())).containsOnly(false);
        assertBalances(guest, 0L, 5_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    // ---------------------------------------------------------------------------------------------
    // 반환·지급 혼합
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("반환 → 지급 → 반환을 섞어도 HP 종류·충전 건별 결과가 정확하고, 각 처리의 충전 건 내역을 재요청으로 재구성한다")
    void mixed_releasePayoutRelease() {
        Long host = wallet(HOST);
        Long guestA = wallet(11L);
        Long guestB = wallet(12L);
        revenue(host, 2_000L);
        Long a = charge(host, 2_000L, T1);
        Long b = charge(host, 3_000L, T2);
        Long c = charge(host, 3_000L, T3);
        HoldResult hold = hold(HOST, RECRUIT, 9L, 10_000L);   // 수익 2,000 + 충전 a 2,000·b 3,000·c 3,000

        ReleaseResult r1 = release(HOST, RECRUIT, 9L, "R1", 4_000L);        // 충전 c 3,000 + b 1,000
        PayoutResult p1 = payout(HOST, RECRUIT, 9L, "A", 11L, RECRUIT_SOURCE, 101L, 3_000L); // 수익 2,000 + 충전 a 1,000
        PayoutResult p2 = payout(HOST, RECRUIT, 9L, "B", 12L, RECRUIT_SOURCE, 102L, 1_500L); // 충전 a 1,000 + b 500
        ReleaseResult r2 = holdFacade.release(ReleaseCommand.full(HOST, RECRUIT, 9L, "R2")); // 충전 b 1,500

        assertThat(r1.chargeRestorations()).containsExactly(new ChargeRestoration(c, 3_000L),
                new ChargeRestoration(b, 1_000L));
        assertThat(p1.paidRevenueHp()).isEqualTo(2_000L);
        assertThat(p1.chargeConsumptions()).containsExactly(new ChargeConsumption(a, 1_000L));
        assertThat(p2.chargeConsumptions()).containsExactly(new ChargeConsumption(a, 1_000L),
                new ChargeConsumption(b, 500L));
        assertThat(r2.chargeRestorations()).containsExactly(new ChargeRestoration(b, 1_500L));
        assertThat(r2.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(usageRows(hold.holdId())).containsExactly(
                a + ":2000:0:2000:0:CONSUMED", b + ":3000:0:500:2500:MIXED", c + ":3000:0:0:3000:RELEASED");
        // 소진한 원금(a 2,000·b 500)은 돌아오지 않고, 반환한 원금(b 2,500·c 3,000)만 돌아온다.
        assertThat(remainingPrincipal(a)).isZero();
        assertThat(remainingPrincipal(b)).isEqualTo(2_500L);
        assertThat(remainingPrincipal(c)).isEqualTo(3_000L);
        // 각 처리의 충전 건별 내역을 상세 재생으로 다시 얻는다.
        assertThat(release(HOST, RECRUIT, 9L, "R1", 4_000L).chargeRestorations()).isEqualTo(r1.chargeRestorations());
        assertThat(payout(HOST, RECRUIT, 9L, "A", 11L, RECRUIT_SOURCE, 101L, 3_000L).chargeConsumptions())
                .isEqualTo(p1.chargeConsumptions());
        assertThat(payout(HOST, RECRUIT, 9L, "B", 12L, RECRUIT_SOURCE, 102L, 1_500L).chargeConsumptions())
                .isEqualTo(p2.chargeConsumptions());
        assertBalances(host, 5_500L, 0L, 0L, 0L);
        assertInvariants(host, guestA, guestB);
    }

    @Test
    @DisplayName("지급 → 반환 순서도 남은 HP 종류별 보류와 배분 누적값이 정확하다")
    void mixed_payoutThenRelease() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 1_000L);
        Long a = charge(host, 2_000L, T1);
        Long b = charge(host, 2_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 5_000L);   // 수익 1,000 + 충전 a 2,000·b 2,000

        PayoutResult paid = payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 2_000L); // 수익 1,000 + a 1,000
        ReleaseResult released = release(HOST, REQUEST, 7L, "R1", 2_500L);                    // b 2,000 + a 500

        assertThat(paid.chargeConsumptions()).containsExactly(new ChargeConsumption(a, 1_000L));
        assertThat(released.chargeRestorations()).containsExactly(new ChargeRestoration(b, 2_000L),
                new ChargeRestoration(a, 500L));
        assertThat(released.remainingHeldHp()).isEqualTo(500L);
        assertThat(holdRow(hold.holdId())).containsEntry("held_charge_hp", 500L).containsEntry("held_revenue_hp", 0L)
                .containsEntry("status", "PARTIALLY_PROCESSED");
        assertThat(usageRows(hold.holdId())).containsExactly(
                a + ":2000:500:1000:500:PARTIALLY_PROCESSED", b + ":2000:0:0:2000:RELEASED");
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("배분 누적값이 반환·지급 재생 결과와 다르면 지급도 HOLD_STATE_INCONSISTENT로 거절한다")
    void mixed_historyMismatch() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 1_000L, T1);
        charge(host, 1_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 2_000L);
        payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 500L);
        // FIFO면 첫 배분에서 소진했어야 하는데 두 번째 배분에서 소진한 것처럼 바꾼다(합계는 같다).
        jdbcTemplate.update("""
                UPDATE billing.point_charge_usage
                SET remaining_held_hp = remaining_held_hp + CASE WHEN id = ? THEN 500 ELSE -500 END,
                    consumed_hp = consumed_hp + CASE WHEN id = ? THEN -500 ELSE 500 END
                WHERE point_hold_id = ?""", firstUsageId(hold), firstUsageId(hold), hold.holdId());
        Map<String, Object> before = snapshot();

        assertBillingError(() -> holdFacade.release(ReleaseCommand.full(HOST, REQUEST, 7L, "R1")),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);
        assertBillingError(() -> payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 500L),
                BillingErrorCode.HOLD_STATE_INCONSISTENT);

        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // 위약금
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("정산 기준액 10,000 HP의 위약금은 호스트 반환 7,000 + 게스트 지급 3,000이며 한 트랜잭션에 기록된다")
    void penalty_10000() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        Long first = charge(host, 4_000L, T1);
        Long second = charge(host, 6_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 10_000L);

        PenaltyResult result = penalty("C1", 10_000L);

        assertThat(result.penaltyHp()).isEqualTo(3_000L);
        assertThat(result.releasedHp()).isEqualTo(7_000L);
        assertThat(result.release().releasedChargeHp()).isEqualTo(7_000L);
        assertThat(result.release().chargeRestorations()).containsExactly(new ChargeRestoration(second, 6_000L),
                new ChargeRestoration(first, 1_000L));
        assertThat(result.payout().paidHp()).isEqualTo(3_000L);
        assertThat(result.payout().payoutType()).isEqualTo(com.hapbang.billing.domain.PayoutType.PENALTY);
        assertThat(result.payout().chargeConsumptions()).containsExactly(new ChargeConsumption(first, 3_000L));
        assertThat(result.payout().holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        String base = "PENALTY:" + hold.holdId() + ":C1";
        assertThat(detailRows(hold.holdId())).containsExactly(base + ":RELEASE RELEASE 7000 0",
                base + ":PAYOUT PAYOUT 3000 0");
        assertThat(settlementRows(hold.holdId())).containsExactly(
                base + " " + GUEST + " " + guest + " APPEARANCE_REQUEST 7 PENALTY 3000 3000 COMPLETED");
        assertThat(ledgerLines(base + ":RELEASE")).hasSize(2);
        assertThat(ledgerLines(base + ":PAYOUT")).containsExactly(
                host + " " + base + ":PAYOUT:1 CHARGE HELD -3000",
                guest + " " + base + ":PAYOUT:2 REVENUE AVAILABLE 3000");
        assertBalances(host, 7_000L, 0L, 0L, 0L);
        assertBalances(guest, 0L, 3_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("정산 기준액 10,001 HP는 위약금 3,000(소수점 버림)·반환 7,001이고, 수익·충전 혼합 보류도 반환은 충전 우선·지급은 수익 우선이다")
    void penalty_10001_mixed() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 4_001L);
        charge(host, 6_000L, T1);
        hold(HOST, REQUEST, 7L, 10_001L);   // 수익 4,001 + 충전 6,000

        PenaltyResult result = penalty("C1", 10_001L);

        assertThat(result.penaltyHp()).isEqualTo(3_000L);
        assertThat(result.releasedHp()).isEqualTo(7_001L);
        assertThat(result.release().releasedChargeHp()).isEqualTo(6_000L);
        assertThat(result.release().releasedRevenueHp()).isEqualTo(1_001L);
        assertThat(result.payout().paidRevenueHp()).isEqualTo(3_000L);
        assertThat(result.payout().paidChargeHp()).isZero();
        assertBalances(host, 6_000L, 1_001L, 0L, 0L);
        assertBalances(guest, 0L, 3_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("모집 보류에서 게스트 한 명의 출연료만 기준으로 위약금을 정산하면 나머지 보류는 그대로 남는다")
    void penalty_recruitOneGuest() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 60_000L, T1);
        hold(HOST, RECRUIT, 9L, 60_000L);

        PenaltyResult result = holdFacade.settlePenalty(
                new PenaltyCommand(HOST, RECRUIT, 9L, "C1", GUEST, RECRUIT_SOURCE, 101L, 20_000L));

        assertThat(result.payout().paidHp()).isEqualTo(6_000L);
        assertThat(result.releasedHp()).isEqualTo(14_000L);
        assertThat(result.payout().remainingHeldHp()).isEqualTo(40_000L);
        assertThat(result.payout().holdStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        assertBalances(host, 14_000L, 0L, 40_000L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("한 모집 보류에서 게스트 A는 위약금 정산, 게스트 B는 정상 지급, 남은 자리는 반환해도 각 정산이 서로 침범하지 않는다")
    void recruit_penaltyForOneGuest_normalForAnother() {
        Long host = wallet(HOST);
        Long guestA = wallet(11L);
        Long guestB = wallet(12L);
        revenue(host, 5_000L);
        charge(host, 30_000L, T1);
        charge(host, 30_000L, T2);
        HoldResult hold = hold(HOST, RECRUIT, 9L, 60_000L);   // 수익 5,000 + 충전 55,000, 게스트 3명 × 계약 20,000

        PenaltyResult penaltyA = holdFacade.settlePenalty(
                new PenaltyCommand(HOST, RECRUIT, 9L, "CANCEL-A", 11L, RECRUIT_SOURCE, 101L, 20_000L));
        PayoutResult normalB = payout(HOST, RECRUIT, 9L, "CONFIRM-B", 12L, RECRUIT_SOURCE, 102L, 20_000L);
        ReleaseResult rest = holdFacade.release(ReleaseCommand.full(HOST, RECRUIT, 9L, "UNFILLED"));

        assertThat(penaltyA.penaltyHp()).isEqualTo(6_000L);
        assertThat(penaltyA.releasedHp()).isEqualTo(14_000L);
        assertThat(normalB.paidHp()).isEqualTo(20_000L);
        assertThat(rest.releasedHp()).isEqualTo(20_000L);
        assertThat(rest.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(settlementRows(hold.holdId())).containsExactly(
                "PENALTY:" + hold.holdId() + ":CANCEL-A 11 " + guestA + " RECRUIT_APPEARANCE 101 PENALTY 6000 6000 COMPLETED",
                "PAYOUT:" + hold.holdId() + ":CONFIRM-B 12 " + guestB + " RECRUIT_APPEARANCE 102 NORMAL 20000 20000 COMPLETED");
        assertBalances(guestA, 0L, 6_000L, 0L, 0L);
        assertBalances(guestB, 0L, 20_000L, 0L, 0L);
        // 호스트 충전 HP 가용: 보류 후 5,000 + 위약금 반환 14,000 + 빈자리 반환 20,000. 위약금 지급이 수익 HP 5,000을 먼저 썼다.
        assertBalances(host, 39_000L, 0L, 0L, 0L);
        assertInvariants(host, guestA, guestB);
    }

    @Test
    @DisplayName("같은 게스트·같은 출연에 정상 지급 후 위약금, 위약금 후 정상 지급은 지급 유형이 달라도 PAYOUT_ALREADY_SETTLED로 거절한다")
    void sameSource_normalAndPenaltyExclusive() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 60_000L, T1);
        hold(HOST, RECRUIT, 9L, 60_000L);
        payout(HOST, RECRUIT, 9L, "CONFIRM-101", GUEST, RECRUIT_SOURCE, 101L, 20_000L);
        holdFacade.settlePenalty(new PenaltyCommand(HOST, RECRUIT, 9L, "CANCEL-102", GUEST, RECRUIT_SOURCE, 102L,
                20_000L));
        Map<String, Object> before = snapshot();

        assertBillingError(() -> holdFacade.settlePenalty(new PenaltyCommand(HOST, RECRUIT, 9L, "CANCEL-101", GUEST,
                RECRUIT_SOURCE, 101L, 20_000L)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "CONFIRM-102", GUEST, RECRUIT_SOURCE, 102L, 20_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        // 지급 요청 ID·취소 ID만 바꿔 같은 출연에 다시 보내도 막는다.
        assertBillingError(() -> payout(HOST, RECRUIT, 9L, "CONFIRM-101-2", GUEST, RECRUIT_SOURCE, 101L, 20_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBillingError(() -> holdFacade.settlePenalty(new PenaltyCommand(HOST, RECRUIT, 9L, "CANCEL-102-2", GUEST,
                RECRUIT_SOURCE, 102L, 20_000L)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);

        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("위약금이 0 HP가 되는 기준액(3 HP)은 PENALTY_AMOUNT_ZERO로 거절하고 지갑·원장·보류·배분·지급 건을 바꾸지 않는다")
    void penalty_zeroRejected() {
        Long host = wallet(HOST);
        wallet(GUEST);
        revenue(host, 3L);
        hold(HOST, REQUEST, 7L, 3L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> penalty("C1", 3L), BillingErrorCode.PENALTY_AMOUNT_ZERO);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("계약 출연료 15,000 HP의 위약금은 게스트 지급 4,500 + 호스트 반환 10,500이다")
    void penalty_15000() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 2_000L);
        charge(host, 13_000L, T1);
        hold(HOST, REQUEST, 7L, 15_000L);   // 수익 2,000 + 충전 13,000

        PenaltyResult result = holdFacade.settlePenalty(new PenaltyCommand(HOST, REQUEST, 7L, "C1", GUEST,
                REQUEST_SOURCE, 7001L, 15_000L));

        assertThat(result.penaltyHp()).isEqualTo(4_500L);
        assertThat(result.releasedHp()).isEqualTo(10_500L);
        assertThat(result.release().releasedChargeHp()).isEqualTo(10_500L);
        assertThat(result.payout().paidRevenueHp()).isEqualTo(2_000L);
        assertThat(result.payout().paidChargeHp()).isEqualTo(2_500L);
        assertBalances(host, 10_500L, 0L, 0L, 0L);
        assertBalances(guest, 0L, 4_500L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("출연 요청(1:1) 보류는 출연 계약 ID를 원천으로 지급하고, 계약이 하나뿐이라 다른 원천으로 두 번째 지급·위약금은 거절한다")
    void request_contractSettlesOnce() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 20_000L, T1);
        HoldResult hold = hold(HOST, REQUEST, 7L, 20_000L);

        PayoutResult paid = payout(HOST, REQUEST, 7L, "CONFIRM", GUEST, REQUEST_SOURCE, 7001L, 15_000L);
        Map<String, Object> before = snapshot();

        assertThat(paid.sourceId()).isEqualTo(7001L);
        assertBillingError(() -> payout(HOST, REQUEST, 7L, "CONFIRM-2", GUEST, REQUEST_SOURCE, 7002L, 5_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBillingError(() -> holdFacade.settlePenalty(new PenaltyCommand(HOST, REQUEST, 7L, "CANCEL", GUEST,
                REQUEST_SOURCE, 7003L, 5_000L)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(settlementRows(hold.holdId())).hasSize(1);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("같은 출연 계약 ID가 다른 보류(같은 호스트·다른 호스트)로 다시 오면 정상 지급·위약금 모두 PAYOUT_ALREADY_SETTLED로 거절한다")
    void sameContractAcrossHolds_rejected() {
        Long host = wallet(HOST);
        Long otherHost = wallet(3L);
        Long guest = wallet(GUEST);
        charge(host, 40_000L, T1);
        charge(otherHost, 20_000L, T1);
        hold(HOST, RECRUIT, 9L, 20_000L);
        hold(HOST, RECRUIT, 10L, 20_000L);
        holdFacade.hold(new HoldCommand(3L, RECRUIT, 11L, 20_000L));
        payout(HOST, RECRUIT, 9L, "CONFIRM-101", GUEST, RECRUIT_SOURCE, 101L, 15_000L);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> payout(HOST, RECRUIT, 10L, "CONFIRM-101", GUEST, RECRUIT_SOURCE, 101L, 15_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBillingError(() -> holdFacade.settlePenalty(new PenaltyCommand(HOST, RECRUIT, 10L, "CANCEL-101", GUEST,
                RECRUIT_SOURCE, 101L, 15_000L)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBillingError(() -> payout(3L, RECRUIT, 11L, "CONFIRM-101", GUEST, RECRUIT_SOURCE, 101L, 15_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);

        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(host, otherHost, guest);
    }

    @Test
    @DisplayName("같은 취소 사건을 다시 보내면 추가 반환·지급 없이 처음 정산 결과를 돌려주고, 같은 취소 ID에 다른 기준액·받는 회원·원천은 충돌이다")
    void penalty_idempotency() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        wallet(3L);
        revenue(host, 2_000L);
        charge(host, 8_000L, T1);
        hold(HOST, RECRUIT, 9L, 10_000L);
        PenaltyResult first = holdFacade.settlePenalty(
                new PenaltyCommand(HOST, RECRUIT, 9L, "C1", GUEST, RECRUIT_SOURCE, 101L, 5_000L));
        Map<String, Object> before = snapshot();

        PenaltyResult retried = holdFacade.settlePenalty(
                new PenaltyCommand(HOST, RECRUIT, 9L, "C1", GUEST, RECRUIT_SOURCE, 101L, 5_000L));

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison()
                .ignoringFields("replayed", "release.replayed", "payout.replayed").isEqualTo(first);
        assertBillingError(() -> holdFacade.settlePenalty(
                        new PenaltyCommand(HOST, RECRUIT, 9L, "C1", GUEST, RECRUIT_SOURCE, 101L, 6_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> holdFacade.settlePenalty(
                        new PenaltyCommand(HOST, RECRUIT, 9L, "C1", 3L, RECRUIT_SOURCE, 101L, 5_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> holdFacade.settlePenalty(
                        new PenaltyCommand(HOST, RECRUIT, 9L, "C1", GUEST, RECRUIT_SOURCE, 102L, 5_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        // 다른 취소 ID로 같은 게스트 출연에 위약금을 두 번 정산하지 않는다.
        assertBillingError(() -> holdFacade.settlePenalty(
                        new PenaltyCommand(HOST, RECRUIT, 9L, "C2", GUEST, RECRUIT_SOURCE, 101L, 5_000L)),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("정산 기준액이 남은 보류보다 크면 PENALTY_BASE_EXCEEDS_HELD, 이미 끝난 보류에 다른 취소 ID는 HOLD_ALREADY_CLOSED다")
    void penalty_rejects() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 10_000L, T1);
        hold(HOST, REQUEST, 7L, 10_000L);
        release(HOST, REQUEST, 7L, "R1", 1_000L);
        Map<String, Object> before = snapshot();

        // 원래 계약 금액(10,000)을 기준으로 하면 이미 반환한 1,000까지 다시 정산하게 되므로 거절한다.
        assertBillingError(() -> penalty("C1", 10_000L), BillingErrorCode.PENALTY_BASE_EXCEEDS_HELD);
        assertThat(snapshot()).isEqualTo(before);

        penalty("C1", 9_000L);
        assertBillingError(() -> penalty("C2", 9_000L), BillingErrorCode.HOLD_ALREADY_CLOSED);
    }

    @Test
    @DisplayName("같은 취소 사건이 동시에 여러 번 들어와도 한 번만 정산한다")
    void penalty_concurrentSameCancellation() throws Exception {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 10_000L, T1);
        HoldResult hold = hold(HOST, REQUEST, 7L, 10_000L);
        List<Callable<PenaltyResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> penalty("C1", 10_000L));
        }

        List<PenaltyResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(detailRows(hold.holdId())).hasSize(2);
        assertThat(settlementRows(hold.holdId())).hasSize(1);
        assertBalances(host, 7_000L, 0L, 0L, 0L);
        assertBalances(guest, 0L, 3_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("반환은 성공하고 위약금 지급 상세 저장이 실패하면 반환까지 모두 롤백되고, 같은 취소 ID로 다시 정산할 수 있다")
    void penalty_payoutFailure_rollsBackRelease() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 10_000L, T1);
        hold(HOST, REQUEST, 7L, 10_000L);
        Map<String, Object> before = snapshot();

        failOn("point_hold_detail", "INSERT", "NEW.action_type = 'PAYOUT'",
                () -> assertThatThrownBy(() -> penalty("C1", 10_000L)).hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
        assertThat(penalty("C1", 10_000L).replayed()).isFalse();
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("위약금 지급 건 저장이 실패하면 반환·지급이 모두 롤백된다")
    void penalty_settlementFailure_rollsBackAll() {
        assertPenaltyRollbackOn("settlement", "INSERT", null);
    }

    @Test
    @DisplayName("위약금의 지급 원장 기록이 실패하면 먼저 기록한 반환 원장까지 모두 롤백된다")
    void penalty_payoutLedgerFailure_rollsBackAll() {
        assertPenaltyRollbackOn("point_ledger", "INSERT", "NEW.transaction_type = 'PAYOUT'");
    }

    @Test
    @DisplayName("정상 지급의 지급 건·원장 저장이 실패하면 보류·배분·지급 상세·두 지갑 변경이 모두 롤백된다")
    void payout_failures_rollBackAll() {
        for (String table : List.of("settlement", "point_ledger", "point_charge_usage", "point_hold")) {
            Long host = wallet(100L + sequence.incrementAndGet());
            Long guest = wallet(200L + sequence.incrementAndGet());
            Long hostUser = walletRepository.findById(host).orElseThrow().getUserId();
            Long guestUser = walletRepository.findById(guest).orElseThrow().getUserId();
            revenue(host, 1_000L);
            charge(host, 2_000L, T1);
            long ref = sequence.incrementAndGet();
            holdFacade.hold(new HoldCommand(hostUser, REQUEST, ref, 3_000L));
            Map<String, Object> before = snapshot();
            String event = table.equals("point_charge_usage") || table.equals("point_hold") ? "UPDATE" : "INSERT";

            failOn(table, event, null, () -> assertThatThrownBy(() -> holdFacade.payout(
                    PayoutCommand.full(hostUser, REQUEST, ref, "P1", guestUser, REQUEST_SOURCE, ref)))
                    .hasStackTraceContaining("test failure"));

            assertThat(snapshot()).as(table).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("호출한 쪽 트랜잭션이 롤백되면 위약금 정산도 모두 롤백된다")
    void penalty_joinsCallerTransaction() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 10_000L, T1);
        hold(HOST, REQUEST, 7L, 10_000L);
        Map<String, Object> before = snapshot();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            penalty("C1", 10_000L);
            throw new IllegalStateException("이후 업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 지급 요청이 동시에 여러 번 들어와도 한 번만 지급한다")
    void concurrent_samePayout() throws Exception {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 3_000L, T1);
        charge(host, 3_000L, T2);
        HoldResult hold = hold(HOST, REQUEST, 7L, 5_000L);
        List<Callable<PayoutResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> payout(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7L, 4_000L));
        }

        List<PayoutResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(settlementRows(hold.holdId())).hasSize(1);
        assertBalances(guest, 0L, 4_000L, 0L, 0L);
        assertInvariants(host, guest);
    }

    @Test
    @DisplayName("다른 호스트의 보류가 같은 출연 계약을 동시에 지급하면, 앱 확인을 통과한 늦은 쪽을 DB UNIQUE가 막고 그 쪽 변경은 모두 롤백된다")
    void concurrent_sameContractAcrossHosts_dbUnique() throws Exception {
        Long hostA = wallet(HOST);
        Long hostB = wallet(3L);
        Long guest = wallet(GUEST);
        // 받는 지갑이 같으면 받는 지갑 락으로 이미 직렬화되므로, 출연 쪽 오류로 다른 게스트에게 같은 계약 ID가 온 경우를 만든다.
        Long otherGuest = wallet(4L);
        charge(hostA, 20_000L, T1);
        charge(hostB, 20_000L, T1);
        hold(HOST, RECRUIT, 9L, 20_000L);
        holdFacade.hold(new HoldCommand(3L, RECRUIT, 10L, 20_000L));
        Map<String, Object> hostBBefore = balances(hostB);
        CountDownLatch paidButNotCommitted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // A: 지급하고 커밋하지 않은 채 기다린다(지급 건 INSERT가 UNIQUE 키를 잡고 있다).
            Future<PayoutResult> first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    status -> {
                        PayoutResult result = payout(HOST, RECRUIT, 9L, "CONFIRM-777", GUEST, RECRUIT_SOURCE, 777L,
                                15_000L);
                        paidButNotCommitted.countDown();
                        await(commit);
                        return result;
                    }));
            await(paidButNotCommitted);
            // B: A가 커밋 전이라 앱의 원천 확인을 통과하고, 같은 원천의 지급 건 INSERT에서 A의 커밋을 기다린다.
            Future<Object> second = executor.submit(() -> {
                try {
                    return payout(3L, RECRUIT, 10L, "CONFIRM-777", 4L, RECRUIT_SOURCE, 777L, 15_000L);
                } catch (BillingException exception) {
                    return exception.getErrorCode();
                }
            });
            awaitBlockedOnLock();
            commit.countDown();

            assertThat(first.get(60, TimeUnit.SECONDS).replayed()).isFalse();
            assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.settlement WHERE source_type = 'RECRUIT_APPEARANCE' AND source_id = 777",
                Integer.class)).isEqualTo(1);
        // B 호스트의 보류·배분·상세·원장·지갑은 바뀌지 않았다.
        assertThat(balances(hostB)).isEqualTo(hostBBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_hold_detail d JOIN billing.point_hold h ON h.id = d.point_hold_id
                WHERE h.wallet_id = ?""", Integer.class, hostB)).isZero();
        assertBalances(guest, 0L, 15_000L, 0L, 0L);
        // 같은 요청 재시도: 이긴 쪽은 처음 결과, 진 쪽은 다시 보내도 같은 원천이라 거절된다.
        assertThat(payout(HOST, RECRUIT, 9L, "CONFIRM-777", GUEST, RECRUIT_SOURCE, 777L, 15_000L).replayed()).isTrue();
        assertBillingError(() -> payout(3L, RECRUIT, 10L, "CONFIRM-777", 4L, RECRUIT_SOURCE, 777L, 15_000L),
                BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        assertBalances(otherGuest, 0L, 0L, 0L, 0L);
        assertInvariants(hostA, hostB, guest, otherGuest);
    }

    @Test
    @DisplayName("여러 호스트의 보류가 같은 출연 계약에 정상 지급·위약금을 동시에 요청해도 지급 건은 하나만 생기고 나머지는 모두 롤백된다")
    void concurrent_sameContractAcrossHosts_mixed() throws Exception {
        List<Long> hosts = new ArrayList<>();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long hostUser = 100L + i;
            // 호스트마다 받는 게스트가 달라 받는 지갑 락으로 직렬화되지 않는다(DB UNIQUE가 막아야 한다).
            long guestUser = 200L + i;
            Long host = wallet(hostUser);
            hosts.add(host);
            hosts.add(wallet(guestUser));
            charge(host, 20_000L, T1);
            long ref = 300L + i;
            holdFacade.hold(new HoldCommand(hostUser, RECRUIT, ref, 20_000L));
            boolean normal = i % 2 == 0;
            tasks.add(() -> {
                try {
                    if (normal) {
                        payout(hostUser, RECRUIT, ref, "CONFIRM-888", guestUser, RECRUIT_SOURCE, 888L, 15_000L);
                    } else {
                        holdFacade.settlePenalty(new PenaltyCommand(hostUser, RECRUIT, ref, "CANCEL-888", guestUser,
                                RECRUIT_SOURCE, 888L, 15_000L));
                    }
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.PAYOUT_ALREADY_SETTLED);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement WHERE source_id = 888",
                Integer.class)).isEqualTo(1);
        // 처리된 보류는 하나뿐이고 나머지 호스트의 보류 20,000은 그대로다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_hold WHERE held_charge_hp + held_revenue_hp = 20000",
                Integer.class)).isEqualTo(THREADS - 1);
        assertInvariants(hosts.toArray(Long[]::new));
    }

    @Test
    @DisplayName("같은 보류에 서로 다른 게스트 지급이 동시에 들어와도 남은 보류를 넘는 지급은 거절되고 원금을 중복 소진하지 않는다")
    void concurrent_distinctPayouts() throws Exception {
        Long host = wallet(HOST);
        List<Long> chargeIds = List.of(charge(host, 10_000L, T1), charge(host, 10_000L, T2),
                charge(host, 10_000L, T3));
        HoldResult hold = hold(HOST, RECRUIT, 9L, 30_000L);
        List<Long> guests = new ArrayList<>();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long guestUser = 100L + i;
            guests.add(wallet(guestUser));
            long source = 1_000L + i;
            tasks.add(() -> {
                try {
                    payout(HOST, RECRUIT, 9L, "G-" + source, guestUser, RECRUIT_SOURCE, source, 7_000L);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.PAYOUT_AMOUNT_EXCEEDS_HELD);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(4);
        assertThat(settlementRows(hold.holdId())).hasSize(4);
        assertThat(chargeIds.stream().mapToLong(this::remainingPrincipal).sum()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(consumed_hp) FROM billing.point_charge_usage WHERE point_hold_id = ?", Long.class,
                hold.holdId())).isEqualTo(28_000L);
        assertBalances(host, 0L, 0L, 2_000L, 0L);
        List<Long> all = new ArrayList<>(guests);
        all.add(host);
        assertInvariants(all.toArray(Long[]::new));
    }

    @Test
    @DisplayName("같은 보류에서 반환과 지급이 경쟁해도 처리 합계가 보류를 넘지 않고 이력이 재구성된다")
    void concurrent_releaseAndPayout() throws Exception {
        Long host = wallet(HOST);
        revenue(host, 3_000L);
        charge(host, 4_000L, T1);
        charge(host, 3_000L, T2);
        HoldResult hold = hold(HOST, RECRUIT, 9L, 10_000L);
        List<Long> guests = new ArrayList<>();
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long index = i;
            if (i % 2 == 0) {
                tasks.add(() -> {
                    try {
                        return release(HOST, RECRUIT, 9L, "R" + index, 1_500L).releasedHp();
                    } catch (BillingException exception) {
                        return 0L;
                    }
                });
            } else {
                long guestUser = 100L + i;
                guests.add(wallet(guestUser));
                tasks.add(() -> {
                    try {
                        return payout(HOST, RECRUIT, 9L, "P" + index, guestUser, RECRUIT_SOURCE, index, 1_500L)
                                .paidHp();
                    } catch (BillingException exception) {
                        return 0L;
                    }
                });
            }
        }

        List<Long> processed = runTogether(tasks);

        assertThat(processed.stream().mapToLong(Long::longValue).sum()).isEqualTo(9_000L);
        // 남은 보류 전부를 마지막 반환으로 처리하면 재생 대조를 통과한다.
        assertThat(holdFacade.release(ReleaseCommand.full(HOST, RECRUIT, 9L, "FINAL")).releasedHp()).isEqualTo(1_000L);
        List<Long> all = new ArrayList<>(guests);
        all.add(host);
        assertInvariants(all.toArray(Long[]::new));
    }

    @Test
    @DisplayName("여러 호스트가 같은 게스트에게 동시에 지급해도 게스트 수익 HP가 정확히 합산된다")
    void concurrent_sameGuestFromManyHosts() throws Exception {
        Long guest = wallet(GUEST);
        List<Long> hosts = new ArrayList<>();
        List<Callable<PayoutResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long hostUser = 100L + i;
            Long host = wallet(hostUser);
            hosts.add(host);
            charge(host, 5_000L, T1);
            long ref = 500L + i;
            holdFacade.hold(new HoldCommand(hostUser, REQUEST, ref, 5_000L));
            tasks.add(() -> holdFacade.payout(
                    PayoutCommand.full(hostUser, REQUEST, ref, "P1", GUEST, REQUEST_SOURCE, ref)));
        }

        runTogether(tasks);

        assertBalances(guest, 0L, THREADS * 5_000L, 0L, 0L);
        hosts.add(guest);
        assertInvariants(hosts.toArray(Long[]::new));
    }

    @Test
    @DisplayName("두 회원이 서로에게 동시에 지급해도(반대 방향) 지갑 ID 순서로 잠가 교착 없이 모두 처리된다")
    void concurrent_bidirectional() throws Exception {
        Long a = wallet(HOST);
        Long b = wallet(GUEST);
        charge(a, 20_000L, T1);
        charge(b, 20_000L, T1);
        List<Callable<PayoutResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long refA = 100L + i;
            long refB = 200L + i;
            hold(HOST, REQUEST, refA, 1_000L);
            hold(GUEST, REQUEST, refB, 1_000L);
            tasks.add(() -> holdFacade.payout(PayoutCommand.full(HOST, REQUEST, refA, "P", GUEST, REQUEST_SOURCE, refA)));
            tasks.add(() -> holdFacade.payout(PayoutCommand.full(GUEST, REQUEST, refB, "P", HOST, REQUEST_SOURCE, refB)));
        }

        List<PayoutResult> results = runTogether(tasks);

        assertThat(results).hasSize(10).allMatch(result -> result.holdStatus() == PointHoldStatus.PAID);
        assertBalances(a, 15_000L, 5_000L, 0L, 0L);
        assertBalances(b, 15_000L, 5_000L, 0L, 0L);
        assertInvariants(a, b);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private HoldResult hold(Long userId, BillingReferenceType type, Long referenceId, long amountHp) {
        return holdFacade.hold(new HoldCommand(userId, type, referenceId, amountHp));
    }

    private ReleaseResult release(Long userId, BillingReferenceType type, Long referenceId, String requestId,
            long amountHp) {
        return holdFacade.release(ReleaseCommand.partial(userId, type, referenceId, requestId, amountHp));
    }

    private PayoutResult payout(Long payer, BillingReferenceType type, Long referenceId, String requestId,
            Long receiver, SettlementSourceType sourceType, Long sourceId, long amountHp) {
        return holdFacade.payout(PayoutCommand.partial(payer, type, referenceId, requestId, receiver, sourceType,
                sourceId, amountHp));
    }

    // 출연 요청 7(호스트 → 게스트)의 위약금을 정산한다.
    private PenaltyResult penalty(String cancellationId, long baseAmountHp) {
        return holdFacade.settlePenalty(new PenaltyCommand(HOST, REQUEST, 7L, cancellationId, GUEST, REQUEST_SOURCE,
                7L, baseAmountHp));
    }

    // 출연 요청 7 보류(수익 1,000 + 충전 2,000·3,000)에 행 실패 트리거를 걸고 위약금 정산이 실패하면 아무것도 바뀌지 않는지 확인한다.
    private void assertPenaltyRollbackOn(String table, String event, String when) {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        revenue(host, 1_000L);
        charge(host, 2_000L, T1);
        charge(host, 3_000L, T2);
        hold(HOST, REQUEST, 7L, 6_000L);
        Map<String, Object> before = snapshot();

        failOn(table, event, when,
                () -> assertThatThrownBy(() -> penalty("C1", 6_000L)).hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
        assertInvariants(host, guest);
    }

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

    // 수익 HP 가용 잔액을 늘리는 지급 원장을 기록한다(지급 기능과 무관한 기초 잔액).
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    // 테이블에 행 단위 실패 트리거(조건이 있으면 그 조건일 때만)를 잠시 걸고 동작을 실행한 뒤 지운다.
    private void failOn(String table, String event, String when, Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail BEFORE " + event + " ON billing." + table
                + " FOR EACH ROW" + (when == null ? "" : " WHEN (" + when + ")")
                + " EXECUTE FUNCTION billing.test_fail()");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing." + table);
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    // 지갑·충전 원금·보류·상세·배분·지급 건·원장 상태를 비교할 수 있게 모은다.
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
        snapshot.put("settlements", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, paid_amount_hp, status FROM billing.settlement ORDER BY id"));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    private void assertBalances(Long walletId, long charge, long revenue, long heldCharge, long heldRevenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getChargeBalanceHp()).as("chargeBalanceHp").isEqualTo(charge);
        assertThat(wallet.getRevenueBalanceHp()).as("revenueBalanceHp").isEqualTo(revenue);
        assertThat(wallet.getHeldChargeHp()).as("heldChargeHp").isEqualTo(heldCharge);
        assertThat(wallet.getHeldRevenueHp()).as("heldRevenueHp").isEqualTo(heldRevenue);
    }

    // 금융 불변식: 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류 합계 = 보류 잔액, 배분 남은 보류 합계 = 충전 HP 보류 잔액,
    // 보류별 (최초 - 남은 보류) = 상세 합계 = 배분 (소진 + 반환), 지급 상세 합계 = 지급 건 지급액, 지급 작업마다 보낸 쪽 감소 = 받는 쪽 증가.
    private void assertInvariants(Long... walletIds) {
        for (Long walletId : walletIds) {
            assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("원장 대사 " + walletId).isTrue();
            Wallet wallet = walletRepository.findById(walletId).orElseThrow();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(remaining_principal_hp), 0) FROM billing.point_charge
                    WHERE wallet_id = ? AND status IN ('COMPLETED', 'PARTIALLY_REFUNDED')""", Long.class, walletId))
                    .as("충전 원금 합계 = 충전 HP 가용 잔액").isEqualTo(wallet.getChargeBalanceHp());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT coalesce(sum(held_charge_hp), 0) FROM billing.point_hold WHERE wallet_id = ?", Long.class,
                    walletId)).as("보류 충전 HP 합계").isEqualTo(wallet.getHeldChargeHp());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT coalesce(sum(held_revenue_hp), 0) FROM billing.point_hold WHERE wallet_id = ?", Long.class,
                    walletId)).as("보류 수익 HP 합계").isEqualTo(wallet.getHeldRevenueHp());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(u.remaining_held_hp), 0) FROM billing.point_charge_usage u
                    JOIN billing.point_hold h ON h.id = u.point_hold_id WHERE h.wallet_id = ?""", Long.class, walletId))
                    .as("배분 남은 보류 합계 = 충전 HP 보류 잔액").isEqualTo(wallet.getHeldChargeHp());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM billing.point_hold h
                    WHERE h.wallet_id = ? AND (h.original_charge_hp <> (
                            SELECT coalesce(sum(u.amount_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)
                        OR h.original_charge_hp - h.held_charge_hp <> (
                            SELECT coalesce(sum(d.charge_amount_hp), 0) FROM billing.point_hold_detail d WHERE d.point_hold_id = h.id)
                        OR h.original_revenue_hp - h.held_revenue_hp <> (
                            SELECT coalesce(sum(d.revenue_amount_hp), 0) FROM billing.point_hold_detail d WHERE d.point_hold_id = h.id)
                        OR (SELECT coalesce(sum(d.charge_amount_hp), 0) FROM billing.point_hold_detail d
                            WHERE d.point_hold_id = h.id AND d.action_type = 'PAYOUT') <> (
                            SELECT coalesce(sum(u.consumed_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)
                        OR (SELECT coalesce(sum(d.charge_amount_hp), 0) FROM billing.point_hold_detail d
                            WHERE d.point_hold_id = h.id AND d.action_type = 'RELEASE') <> (
                            SELECT coalesce(sum(u.released_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)
                        OR (SELECT coalesce(sum(d.charge_amount_hp + d.revenue_amount_hp), 0) FROM billing.point_hold_detail d
                            WHERE d.point_hold_id = h.id AND d.action_type = 'PAYOUT') <> (
                            SELECT coalesce(sum(s.paid_amount_hp), 0) FROM billing.settlement s WHERE s.point_hold_id = h.id))""",
                    Integer.class, walletId)).as("보류별 배분·상세·지급 건 일치").isZero();
        }
        assertThat(jdbcTemplate.queryForList("""
                SELECT operation_key FROM billing.point_ledger
                WHERE transaction_type = 'PAYOUT' AND operation_key NOT LIKE 'SEED:%'
                GROUP BY operation_key HAVING sum(amount_hp) <> 0""", String.class))
                .as("지급 작업마다 보낸 쪽 감소 = 받는 쪽 증가").isEmpty();
    }

    private long remainingPrincipal(Long chargeId) {
        return jdbcTemplate.queryForObject("SELECT remaining_principal_hp FROM billing.point_charge WHERE id = ?",
                Long.class, chargeId);
    }

    private Map<String, Object> holdRow(Long holdId) {
        return jdbcTemplate.queryForMap("""
                SELECT held_charge_hp, held_revenue_hp, status, released_at FROM billing.point_hold WHERE id = ?""",
                holdId);
    }

    private Long firstUsageId(HoldResult hold) {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM billing.point_charge_usage WHERE point_hold_id = ?", Long.class, hold.holdId());
    }

    // "충전ID:배분액:남은보류:소진:반환:상태"
    private List<String> usageRows(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT charge_id || ':' || amount_hp || ':' || remaining_held_hp || ':' || consumed_hp || ':'
                    || released_hp || ':' || status
                FROM billing.point_charge_usage WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    // "멱등키 처리유형 충전액 수익액"
    private List<String> detailRows(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || action_type || ' ' || charge_amount_hp || ' ' || revenue_amount_hp
                FROM billing.point_hold_detail WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    // "멱등키 받는회원 받는지갑 원천유형 원천ID 지급유형 총액 완료액 상태"
    private List<String> settlementRows(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || receiver_user_id || ' ' || receiver_wallet_id || ' ' || source_type
                    || ' ' || source_id || ' ' || payout_type || ' ' || gross_amount_hp || ' ' || paid_amount_hp
                    || ' ' || status
                FROM billing.settlement WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    // "지갑ID 멱등키 HP종류 버킷 금액"
    private List<String> ledgerLines(String operationKey) {
        return jdbcTemplate.queryForList("""
                SELECT wallet_id || ' ' || idempotency_key || ' ' || point_type || ' ' || balance_bucket || ' ' || amount_hp
                FROM billing.point_ledger WHERE operation_key = ? ORDER BY id""", String.class, operationKey);
    }

    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private Map<String, Object> balances(Long walletId) {
        return jdbcTemplate.queryForMap("""
                SELECT charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet WHERE id = ?""", walletId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    // 다른 트랜잭션이 락(여기서는 같은 UNIQUE 키의 커밋 전 INSERT)을 기다리는 상태가 될 때까지 기다린다.
    private void awaitBlockedOnLock() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("락 대기 상태가 되지 않았습니다.");
    }

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
