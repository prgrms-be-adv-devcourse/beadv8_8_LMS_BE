package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.app.PayoutResult.ChargeConsumption;
import com.hapbang.billing.app.ReleaseResult.ChargeRestoration;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

// 종류별 보류(MIXED·CHARGE_ONLY·REVENUE_ONLY)와 확정 차감(Capture, #241)을 실제 PostgreSQL로 검증한다.
// 매 시나리오 뒤 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류·배분·상세 합계 일치 불변식을 확인한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273)
@WithMockUser(roles = "INTERNAL")
class HoldCaptureIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T00:00:00Z");
    private static final BillingReferenceType REFUND = BillingReferenceType.POINT_REFUND_REQUEST;
    private static final BillingReferenceType EXCHANGE = BillingReferenceType.EXCHANGE;
    private static final BillingReferenceType LECTURE = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final long USER = 1L;
    private static final long SELLER = 2L;

    @Autowired
    InternalHoldFacade internalHoldFacade;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 종류별 보류
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("MIXED는 수익 HP 먼저, CHARGE_ONLY는 충전 HP만(충전 원금 FIFO 배분), REVENUE_ONLY는 수익 HP만 보류하고 종류를 기록한다")
    void holdByType() {
        Long walletId = wallet(USER);
        revenue(walletId, 3_000L);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);

        HoldResult mixed = holdFacade.hold(new HoldCommand(USER, LECTURE, 11L, 4_000L));
        HoldResult refund = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));
        // 수익 HP는 MIXED 보류가 이미 다 썼으므로 남은 수익 HP가 없다 → 환전 보류 전에 수익 HP를 더 만든다
        revenue(walletId, 1_500L);
        HoldResult exchange = internalHoldFacade.hold(new InternalHoldCommand(USER, EXCHANGE, 31L, 1_500L));

        assertThat(List.of(mixed.revenueHp(), mixed.chargeHp())).containsExactly(3_000L, 1_000L);
        assertThat(refund.revenueHp()).isZero();
        assertThat(refund.chargeHp()).isEqualTo(3_000L);
        // 충전 원금 FIFO: 첫 충전 건 남은 1,000(MIXED가 1,000 사용) → 둘째 충전 건 2,000
        assertThat(refund.chargeAllocations()).containsExactly(new HoldResult.ChargeAllocation(first, 1_000L),
                new HoldResult.ChargeAllocation(second, 2_000L));
        assertThat(List.of(exchange.revenueHp(), exchange.chargeHp())).containsExactly(1_500L, 0L);
        assertThat(holdTypes()).containsExactly("HOLD:LECTURE_ORDER_ITEM:11 MIXED", "HOLD:POINT_REFUND_REQUEST:21 CHARGE_ONLY",
                "HOLD:EXCHANGE:31 REVENUE_ONLY");
        assertBalances(walletId, 1_000L, 0L, 4_000L, 4_500L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("쓸 수 있는 HP 종류의 잔액이 모자라면 다른 종류가 넉넉해도 INSUFFICIENT_BALANCE이고 아무것도 바꾸지 않는다")
    void holdByType_insufficient() {
        Long walletId = wallet(USER);
        revenue(walletId, 10_000L);
        charge(walletId, 1_000L, T1);
        Map<String, Object> before = snapshot();

        assertBillingError(() -> internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 2_000L)),
                BillingErrorCode.INSUFFICIENT_BALANCE);
        Long poorWallet = wallet(SELLER);
        charge(poorWallet, 5_000L, T1);
        Map<String, Object> beforeExchange = snapshot();
        assertBillingError(() -> internalHoldFacade.hold(new InternalHoldCommand(SELLER, EXCHANGE, 31L, 1_000L)),
                BillingErrorCode.INSUFFICIENT_BALANCE);

        assertThat(snapshot()).isEqualTo(beforeExchange);
        assertThat(before.get("holds")).isEqualTo(snapshot().get("holds"));
        assertInvariants(walletId, poorWallet);
    }

    @Test
    @DisplayName("같은 참조 대상 내부 보류 재요청은 처음 결과, 다른 금액은 IDEMPOTENCY_KEY_CONFLICT이며, 강의·출연 참조 유형은 내부 경로로 보류할 수 없다")
    void internalHold_idempotencyAndScope() {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);

        HoldResult first = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));
        HoldResult again = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));

        assertThat(again.replayed()).isTrue();
        assertThat(again.holdId()).isEqualTo(first.holdId());
        assertBillingError(() -> internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 2_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        // 내부 경로는 환불·환전 참조 유형만, BillingPort(shared) 계약은 강의·출연 참조 유형만 받는다
        assertThatThrownBy(() -> new InternalHoldCommand(USER, LECTURE, 11L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HoldCommand(USER, REFUND, 21L, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        assertBalances(walletId, 2_000L, 0L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 확정 차감
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHARGE_ONLY 전체 확정 차감은 충전 HP 보류만 줄이고(원금은 충전 건에 돌아가지 않음) REFUND 원장 1줄, 보류 CAPTURED")
    void capture_chargeOnly_full() {
        Long walletId = wallet(USER);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));

        CaptureResult result = internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 21L, "PG-ALL"));

        assertThat(result.capturedHp()).isEqualTo(5_000L);
        assertThat(result.capturedChargeHp()).isEqualTo(5_000L);
        assertThat(result.chargeConsumptions()).containsExactly(new ChargeConsumption(first, 2_000L),
                new ChargeConsumption(second, 3_000L));
        assertThat(result.remainingHeldHp()).isZero();
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.CAPTURED);
        String key = "CAPTURE:" + hold.holdId() + ":PG-ALL";
        assertThat(ledgerLines(key)).containsExactly(walletId + " CHARGE HELD -5000 REFUND");
        assertThat(remainingPrincipal(first, second)).containsExactly(0L, 0L);
        assertThat(usages(hold.holdId())).containsExactly("2000 0 2000 0 CONSUMED", "3000 0 3000 0 CONSUMED");
        assertBalances(walletId, 0L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("REVENUE_ONLY 전체 확정 차감은 수익 HP 보류만 줄이고 EXCHANGE 원장 1줄, 보류 CAPTURED")
    void capture_revenueOnly_full() {
        Long walletId = wallet(USER);
        revenue(walletId, 5_000L);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, EXCHANGE, 31L, 3_000L));

        CaptureResult result = internalHoldFacade.capture(CaptureCommand.full(USER, EXCHANGE, 31L, "SENT"));

        assertThat(result.capturedRevenueHp()).isEqualTo(3_000L);
        assertThat(result.chargeConsumptions()).isEmpty();
        assertThat(result.holdStatus()).isEqualTo(PointHoldStatus.CAPTURED);
        assertThat(ledgerLines("CAPTURE:" + hold.holdId() + ":SENT"))
                .containsExactly(walletId + " REVENUE HELD -3000 EXCHANGE");
        assertBalances(walletId, 0L, 2_000L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("부분 확정 차감 → 추가 확정 차감 → 남은 보류 반환: 소진은 배분 순서, 반환은 배분 역순으로 원래 충전 건에 복원되고 보류는 MIXED로 끝난다")
    void capture_partialThenCaptureThenRelease() {
        Long walletId = wallet(USER);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));

        CaptureResult partial = internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 1_500L));
        assertThat(partial.holdStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        assertThat(partial.remainingHeldHp()).isEqualTo(3_500L);
        CaptureResult more = internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C2", 1_000L));
        assertThat(more.chargeConsumptions()).containsExactly(new ChargeConsumption(first, 500L),
                new ChargeConsumption(second, 500L));
        ReleaseResult released = internalHoldFacade.release(InternalReleaseCommand.full(USER, REFUND, 21L, "R1"));

        assertThat(released.releasedHp()).isEqualTo(2_500L);
        assertThat(released.chargeRestorations()).containsExactly(new ChargeRestoration(second, 2_500L));
        assertThat(released.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(usages(hold.holdId())).containsExactly("2000 0 2000 0 CONSUMED", "3000 0 500 2500 MIXED");
        assertThat(remainingPrincipal(first, second)).containsExactly(0L, 2_500L);
        assertBalances(walletId, 2_500L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("REVENUE_ONLY 부분 확정 차감 후 반환하면 남은 수익 HP가 가용으로 돌아오고 보류는 MIXED")
    void capture_revenuePartialThenRelease() {
        Long walletId = wallet(USER);
        revenue(walletId, 3_000L);
        internalHoldFacade.hold(new InternalHoldCommand(USER, EXCHANGE, 31L, 3_000L));

        internalHoldFacade.capture(CaptureCommand.partial(USER, EXCHANGE, 31L, "SENT-1", 1_000L));
        ReleaseResult released = internalHoldFacade.release(InternalReleaseCommand.full(USER, EXCHANGE, 31L, "CANCEL"));

        assertThat(released.releasedRevenueHp()).isEqualTo(2_000L);
        assertThat(released.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertBalances(walletId, 0L, 2_000L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 확정 차감 요청 재전송은 처음 결과(재요청 표시)이고 상세·원장·잔액이 늘지 않으며, 같은 ID에 다른 금액은 IDEMPOTENCY_KEY_CONFLICT")
    void capture_idempotency() {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));
        CaptureResult first = internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 2_000L));
        Map<String, Object> after = snapshot();

        CaptureResult again = internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 2_000L));

        assertThat(again.replayed()).isTrue();
        assertThat(again.holdDetailId()).isEqualTo(first.holdDetailId());
        assertThat(again.chargeConsumptions()).isEqualTo(first.chargeConsumptions());
        assertThat(snapshot()).isEqualTo(after);
        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 1_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(snapshot()).isEqualTo(after);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("남은 보류보다 큰 확정 차감은 CAPTURE_AMOUNT_EXCEEDS_HELD, 끝난 보류는 HOLD_ALREADY_CLOSED, 0 이하 금액은 INVALID_POINT_AMOUNT")
    void capture_rejects() {
        Long walletId = wallet(USER);
        charge(walletId, 3_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));
        Map<String, Object> before = snapshot();

        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 3_001L)),
                BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD);
        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 0L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertThat(snapshot()).isEqualTo(before);
        internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 21L, "C1"));
        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 21L, "C2")),
                BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertBillingError(() -> internalHoldFacade.release(InternalReleaseCommand.full(USER, REFUND, 21L, "R1")),
                BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // PG 결제 건(충전 건)별 확정 차감·반환 (환불 부분 성공)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("환불 PG 결제 건별 결과: 성공한 충전 건만 확정 차감, 실패 확정 건만 그 충전 건에 반환, 결과를 모르는 건은 보류로 남는다")
    void capture_byCharge_partialRefund() {
        Long walletId = wallet(USER);
        Long first = charge(walletId, 2_000L, T1);
        Long second = charge(walletId, 3_000L, T2);
        Long third = charge(walletId, 1_000L, T3);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 6_000L));

        // 배분 순서(FIFO)가 아니라 실제 취소에 성공한 둘째 결제 건부터 확정한다
        CaptureResult second0k = internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-" + second, second, 3_000L));
        ReleaseResult firstFailed = internalHoldFacade.release(
                InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "PG-" + first + "-FAILED", first, 2_000L));

        assertThat(second0k.chargeConsumptions()).containsExactly(new ChargeConsumption(second, 3_000L));
        assertThat(second0k.targetChargeId()).isEqualTo(second);
        assertThat(firstFailed.chargeRestorations()).containsExactly(new ChargeRestoration(first, 2_000L));
        assertThat(firstFailed.holdStatus()).isEqualTo(PointHoldStatus.PARTIALLY_PROCESSED);
        // 셋째(결과 불명확)는 그대로 보류
        assertThat(usages(hold.holdId())).containsExactly("2000 0 0 2000 RELEASED", "3000 0 3000 0 CONSUMED",
                "1000 1000 0 0 HELD");
        assertThat(remainingPrincipal(first, second, third)).containsExactly(2_000L, 0L, 0L);
        assertBalances(walletId, 2_000L, 0L, 1_000L, 0L);
        assertInvariants(walletId);

        // 나중에 셋째 결제 건 취소 성공이 확인되면 확정한다. 처리 이력(충전 건 지정 상세 포함)을 재생해 대조한 뒤 처리된다
        CaptureResult thirdOk = internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-" + third, third, 1_000L));

        assertThat(thirdOk.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(detailTargets(hold.holdId())).containsExactly("CAPTURE " + second, "RELEASE " + first,
                "CAPTURE " + third);
        assertBalances(walletId, 2_000L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("충전 건 지정 처리 거절: 배분되지 않은 충전 건 HOLD_CHARGE_NOT_FOUND, 그 배분보다 큰 금액 CAPTURE_AMOUNT_EXCEEDS_HELD, REVENUE_ONLY 보류 HOLD_TYPE_NOT_SUPPORTED")
    void capture_byCharge_rejects() {
        Long walletId = wallet(USER);
        Long first = charge(walletId, 2_000L, T1);
        Long notHeld = charge(walletId, 1_000L, T2);
        revenue(walletId, 1_000L);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 2_000L));
        internalHoldFacade.hold(new InternalHoldCommand(USER, EXCHANGE, 31L, 1_000L));
        Map<String, Object> before = snapshot();

        assertBillingError(() -> internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "X1", notHeld, 1_000L)), BillingErrorCode.HOLD_CHARGE_NOT_FOUND);
        assertBillingError(() -> internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "X2", first, 2_001L)),
                BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD);
        assertBillingError(() -> internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, EXCHANGE, 31L, "X3", first, 500L)), BillingErrorCode.HOLD_TYPE_NOT_SUPPORTED);
        assertBillingError(() -> internalHoldFacade.release(
                InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "X4", notHeld, 1_000L)),
                BillingErrorCode.HOLD_CHARGE_NOT_FOUND);
        // 같은 요청 ID로 지정 충전 건만 바꾸면 다른 요청이다
        internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "X5", first, 500L));
        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "X5", 500L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(((List<?>) snapshot().get("details"))).hasSize(1);
        assertThat(before.get("charges")).isEqualTo(snapshot().get("charges"));
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성·중복 소비 방지
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("서로 다른 확정 차감 요청 8개가 동시에 와도 남은 보류를 넘게 차감하지 않는다(5건 성공, 나머지 거절)")
    void capture_concurrentDistinct() throws Exception {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));

        List<Object> outcomes = concurrently(8, index -> internalHoldFacade.capture(
                CaptureCommand.partial(USER, REFUND, 21L, "C" + index, 1_000L)));

        assertThat(outcomes.stream().filter(CaptureResult.class::isInstance)).hasSize(5);
        assertThat(outcomes.stream().filter(outcome -> !(outcome instanceof CaptureResult)))
                .allMatch(code -> code == BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD
                        || code == BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertThat(holdStatusOf("HOLD:POINT_REFUND_REQUEST:21")).isEqualTo("0 CAPTURED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(amount_hp) FROM billing.point_ledger WHERE transaction_type = 'REFUND'", Long.class))
                .isEqualTo(-5_000L);
        assertBalances(walletId, 0L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 확정 차감 요청이 동시에 여러 번 와도 한 번만 차감한다")
    void capture_concurrentSame() throws Exception {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));

        List<Object> outcomes = concurrently(8, index -> internalHoldFacade.capture(
                CaptureCommand.partial(USER, REFUND, 21L, "SAME", 2_000L)));

        assertThat(outcomes).allMatch(CaptureResult.class::isInstance);
        assertThat(outcomes.stream().filter(outcome -> !((CaptureResult) outcome).replayed())).hasSize(1);
        assertBalances(walletId, 0L, 0L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("같은 보류에 전체 확정 차감과 전체 반환이 동시에 오면 한쪽만 성공하고 HP가 두 번 움직이지 않는다")
    void capture_concurrentWithRelease() throws Exception {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));

        List<Object> outcomes = concurrently(2, index -> index == 0
                ? internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 21L, "PG"))
                : internalHoldFacade.release(InternalReleaseCommand.full(USER, REFUND, 21L, "FAILED")));

        assertThat(outcomes).filteredOn(BillingErrorCode.HOLD_ALREADY_CLOSED::equals).hasSize(1);
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        // 확정 차감이 이겼으면 0(돈은 PG로), 반환이 이겼으면 5,000(가용 복귀). 어느 쪽이든 보류 0, 상세 1건
        assertThat(wallet.getChargeBalanceHp()).isIn(0L, 5_000L);
        assertThat(wallet.getHeldChargeHp()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isEqualTo(1);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("확정 차감과 지급은 같은 보류를 함께 쓰지 못한다: 지급(BillingPort)은 강의·출연 보류만, 확정 차감은 환불·환전 보류만 받는다")
    void captureAndPayout_neverShareHold() {
        Long buyer = wallet(USER);
        Long seller = wallet(SELLER);
        charge(buyer, 5_000L, T1);
        holdFacade.hold(new HoldCommand(USER, LECTURE, 11L, 2_000L));
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));

        // 계약 단계: 지급 요청은 환불 보류를, 확정 차감 요청은 강의 보류를 가리킬 수 없다
        assertThatThrownBy(() -> PayoutCommand.full(USER, REFUND, 21L, "P1", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, 21L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaptureCommand.full(USER, LECTURE, 11L, "C1"))
                .isInstanceOf(IllegalArgumentException.class);
        // 엔진 단계: 강의 보류(MIXED)를 내부 참조로 가리키도록 만든 보류도 확정 차감하지 않는다
        jdbcTemplate.update("UPDATE billing.point_hold SET reference_type = 'POINT_REFUND_REQUEST', reference_id = 99, "
                + "idempotency_key = 'HOLD:POINT_REFUND_REQUEST:99' WHERE idempotency_key = 'HOLD:LECTURE_ORDER_ITEM:11'");
        assertBillingError(() -> internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 99L, "C1")),
                BillingErrorCode.HOLD_TYPE_NOT_SUPPORTED);
        jdbcTemplate.update("UPDATE billing.point_hold SET reference_type = 'LECTURE_ORDER_ITEM', reference_id = 11, "
                + "idempotency_key = 'HOLD:LECTURE_ORDER_ITEM:11' WHERE idempotency_key = 'HOLD:POINT_REFUND_REQUEST:99'");

        // 각자 자기 보류만 끝낸다. 지급(강의)과 확정 차감(환불)이 서로의 보류액을 쓰지 않는다
        holdFacade.payout(PayoutCommand.full(USER, LECTURE, 11L, "LECTURE-PAYOUT-11", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, 11L));
        internalHoldFacade.capture(CaptureCommand.full(USER, REFUND, 21L, "PG"));

        assertThat(holdStatusOf("HOLD:LECTURE_ORDER_ITEM:11")).isEqualTo("0 PAID");
        assertThat(holdStatusOf("HOLD:POINT_REFUND_REQUEST:21")).isEqualTo("0 CAPTURED");
        assertBalances(buyer, 0L, 0L, 0L, 0L);
        assertBalances(seller, 0L, 2_000L, 0L, 0L);
        assertInvariants(buyer, seller);
    }

    @Test
    @DisplayName("강의 보류의 기존 반환·지급은 그대로다: 부분 반환 후 지급이면 MIXED, 상태 결정에 확정 차감이 끼어들지 않는다")
    void mixedHold_regression() {
        Long buyer = wallet(USER);
        Long seller = wallet(SELLER);
        revenue(buyer, 1_000L);
        charge(buyer, 4_000L, T1);
        holdFacade.hold(new HoldCommand(USER, LECTURE, 11L, 5_000L));

        holdFacade.release(ReleaseCommand.partial(USER, LECTURE, 11L, "R1", 1_000L));
        PayoutResult paid = holdFacade.payout(PayoutCommand.full(USER, LECTURE, 11L, "P1", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, 11L));

        assertThat(paid.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertBalances(buyer, 1_000L, 0L, 0L, 0L);
        assertBalances(seller, 0L, 4_000L, 0L, 0L);
        assertInvariants(buyer, seller);
    }

    @Test
    @DisplayName("확정 차감 원장 기록이 실패하면 보류·배분·상세·지갑이 모두 롤백되고, 같은 요청으로 다시 확정 차감할 수 있다")
    void capture_ledgerFailure_rollsBackAll() {
        Long walletId = wallet(USER);
        charge(walletId, 5_000L, T1);
        internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 5_000L));
        Map<String, Object> before = snapshot();

        failOnLedgerInsert(() -> assertThatThrownBy(() -> internalHoldFacade.capture(
                CaptureCommand.partial(USER, REFUND, 21L, "C1", 2_000L))).hasStackTraceContaining("test failure"));

        assertThat(snapshot()).isEqualTo(before);
        CaptureResult retried = internalHoldFacade.capture(CaptureCommand.partial(USER, REFUND, 21L, "C1", 2_000L));
        assertThat(retried.replayed()).isFalse();
        assertBalances(walletId, 0L, 0L, 3_000L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("PG가 충전 건 하나의 20,000 중 10,000만 취소하면 그 10,000만 확정 차감되고 나머지는 그 충전 건 보류로 남아 반환할 수 있다. 충전 건 지정에 금액을 빼면 요청 단계에서 거절한다")
    void capture_byCharge_exactPgAmount() {
        Long walletId = wallet(USER);
        Long chargeId = charge(walletId, 20_000L, T1);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 20_000L));

        CaptureResult canceled = internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-CANCEL-1", chargeId, 10_000L));

        assertThat(canceled.capturedHp()).isEqualTo(10_000L);
        assertThat(canceled.chargeConsumptions()).containsExactly(new ChargeConsumption(chargeId, 10_000L));
        assertThat(canceled.remainingHeldHp()).isEqualTo(10_000L);
        assertThat(usages(hold.holdId())).containsExactly("20000 10000 10000 0 PARTIALLY_PROCESSED");
        assertBalances(walletId, 0L, 0L, 10_000L, 0L);
        // 금액 없는 충전 건 지정은 계약 위반이다(남은 배분 전부를 차감하는 일을 막는다)
        assertThatThrownBy(() -> new CaptureCommand(USER, REFUND, 21L, "PG-CANCEL-2", null, chargeId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalReleaseCommand(USER, REFUND, 21L, "PG-FAILED-2", null, chargeId))
                .isInstanceOf(IllegalArgumentException.class);
        // 남은 배분보다 큰 금액은 다른 충전 건으로 나눠 처리하지 않고 거절한다
        assertBillingError(() -> internalHoldFacade.capture(
                CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-CANCEL-3", chargeId, 10_001L)),
                BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD);

        internalHoldFacade.release(InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "PG-FAILED-2", chargeId, 10_000L));

        assertThat(usages(hold.holdId())).containsExactly("20000 0 10000 10000 MIXED");
        assertThat(remainingPrincipal(chargeId)).containsExactly(10_000L);
        assertBalances(walletId, 10_000L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("FIFO 환불에서 충전 건별 부분 성공·실패·불명확이 섞여도 충전 건별 소진·반환·원금·원장이 정확히 맞는다")
    void capture_byCharge_mixedOutcomes() {
        Long walletId = wallet(USER);
        Long a = charge(walletId, 5_000L, T1);
        Long b = charge(walletId, 10_000L, T2);
        Long c = charge(walletId, 3_000L, T3);
        Long untouched = charge(walletId, 2_000L, Instant.parse("2026-10-04T00:00:00Z"));
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 18_000L));
        assertThat(hold.chargeAllocations()).containsExactly(new HoldResult.ChargeAllocation(a, 5_000L),
                new HoldResult.ChargeAllocation(b, 10_000L), new HoldResult.ChargeAllocation(c, 3_000L));

        // A 전액 성공, B는 10,000 중 6,000만 취소 성공·4,000 실패 확정, C는 결과 불명확
        internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-A", a, 5_000L));
        internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-B", b, 6_000L));
        internalHoldFacade.release(InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "PG-B-FAILED", b, 4_000L));

        assertThat(usages(hold.holdId())).containsExactly("5000 0 5000 0 CONSUMED", "10000 0 6000 4000 MIXED",
                "3000 3000 0 0 HELD");
        assertThat(remainingPrincipal(a, b, c, untouched)).containsExactly(0L, 4_000L, 0L, 2_000L);
        assertThat(ledgerSum("REFUND")).isEqualTo(-11_000L);
        assertBalances(walletId, 6_000L, 0L, 3_000L, 0L);
        assertInvariants(walletId);

        // 나중에 C 실패가 확정되면 C 원금만 돌아온다
        ReleaseResult cFailed = internalHoldFacade.release(
                InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "PG-C-FAILED", c, 3_000L));

        assertThat(cFailed.chargeRestorations()).containsExactly(new ChargeRestoration(c, 3_000L));
        assertThat(cFailed.holdStatus()).isEqualTo(PointHoldStatus.MIXED);
        assertThat(remainingPrincipal(a, b, c, untouched)).containsExactly(0L, 4_000L, 3_000L, 2_000L);
        assertBalances(walletId, 9_000L, 0L, 0L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("서로 다른 충전 건의 확정 차감·반환과 같은 요청 재전송이 동시에 와도 충전 건별로 한 번씩만 처리되고 교착 없이 끝난다")
    void capture_byCharge_concurrent() throws Exception {
        Long walletId = wallet(USER);
        Long a = charge(walletId, 2_000L, T1);
        Long b = charge(walletId, 3_000L, T2);
        Long c = charge(walletId, 4_000L, T3);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 9_000L));

        // 같은 요청을 두 번씩: A 확정 차감, B 반환, C 부분 확정 차감
        List<Object> outcomes = concurrently(6, index -> switch (index % 3) {
            case 0 -> internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-A", a, 2_000L));
            case 1 -> internalHoldFacade.release(
                    InternalReleaseCommand.ofCharge(USER, REFUND, 21L, "PG-B-FAILED", b, 3_000L));
            default -> internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "PG-C", c, 1_500L));
        });

        assertThat(outcomes).noneMatch(BillingErrorCode.class::isInstance);
        assertThat(outcomes.stream().filter(CaptureResult.class::isInstance)
                .filter(result -> ((CaptureResult) result).replayed())).hasSize(2);
        assertThat(outcomes.stream().filter(ReleaseResult.class::isInstance)
                .filter(result -> ((ReleaseResult) result).replayed())).hasSize(1);
        assertThat(usages(hold.holdId())).containsExactly("2000 0 2000 0 CONSUMED", "3000 0 0 3000 RELEASED",
                "4000 2500 1500 0 PARTIALLY_PROCESSED");
        assertThat(remainingPrincipal(a, b, c)).containsExactly(0L, 3_000L, 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isEqualTo(3);
        assertBalances(walletId, 3_000L, 0L, 2_500L, 0L);
        assertInvariants(walletId);
    }

    @Test
    @DisplayName("DB 제약이 최종 방어선이다: CHARGE_ONLY 보류에 수익 HP, 충전 건 지정 상세에 수익 HP는 저장되지 않는다")
    void dbConstraints() {
        Long walletId = wallet(USER);
        Long chargeId = charge(walletId, 3_000L, T1);
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(USER, REFUND, 21L, 3_000L));
        internalHoldFacade.capture(CaptureCommand.ofCharge(USER, REFUND, 21L, "C1", chargeId, 1_000L));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.point_hold SET original_revenue_hp = 1 WHERE id = ?", hold.holdId()))
                .hasMessageContaining("ck_point_hold_5");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.point_hold_detail SET revenue_amount_hp = 1 WHERE point_hold_id = ?", hold.holdId()))
                .hasMessageContaining("ck_point_hold_detail_4");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.point_hold SET hold_type = 'OTHER' WHERE id = ?", hold.holdId()))
                .hasMessageContaining("ck_point_hold_type");
        assertInvariants(walletId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface IndexedAction {
        Object run(int index);
    }

    // 작업 n개를 동시에 시작해 결과(성공 결과 또는 Billing 오류 코드)를 모은다.
    private List<Object> concurrently(int count, IndexedAction action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return action.run(index);
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만든다.
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

    // 수익 HP 가용 잔액을 늘리는 원장을 기록한다(기초 잔액).
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

    private List<String> holdTypes() {
        return jdbcTemplate.queryForList(
                "SELECT idempotency_key || ' ' || hold_type FROM billing.point_hold ORDER BY id", String.class);
    }

    private String holdStatusOf(String key) {
        return jdbcTemplate.queryForObject("SELECT (held_charge_hp + held_revenue_hp) || ' ' || status "
                + "FROM billing.point_hold WHERE idempotency_key = ?", String.class, key);
    }

    private List<String> ledgerLines(String operationKey) {
        return jdbcTemplate.queryForList("""
                SELECT wallet_id || ' ' || point_type || ' ' || balance_bucket || ' ' || amount_hp || ' ' || transaction_type
                FROM billing.point_ledger WHERE operation_key = ? ORDER BY id""", String.class, operationKey);
    }

    private List<String> usages(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT amount_hp || ' ' || remaining_held_hp || ' ' || consumed_hp || ' ' || released_hp || ' ' || status
                FROM billing.point_charge_usage WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    private List<String> detailTargets(Long holdId) {
        return jdbcTemplate.queryForList("""
                SELECT action_type || ' ' || coalesce(target_charge_id::text, '-')
                FROM billing.point_hold_detail WHERE point_hold_id = ? ORDER BY id""", String.class, holdId);
    }

    private long ledgerSum(String transactionType) {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger WHERE transaction_type = ?", Long.class,
                transactionType);
    }

    private List<Long> remainingPrincipal(Long... chargeIds) {
        List<Long> remaining = new ArrayList<>();
        for (Long chargeId : chargeIds) {
            remaining.add(jdbcTemplate.queryForObject(
                    "SELECT remaining_principal_hp FROM billing.point_charge WHERE id = ?", Long.class, chargeId));
        }
        return remaining;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("charges", jdbcTemplate.queryForList(
                "SELECT id, remaining_principal_hp FROM billing.point_charge ORDER BY id"));
        snapshot.put("holds", jdbcTemplate.queryForList("""
                SELECT id, held_charge_hp, held_revenue_hp, status, updated_at FROM billing.point_hold ORDER BY id"""));
        snapshot.put("details", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, charge_amount_hp, revenue_amount_hp FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("usages", jdbcTemplate.queryForList("""
                SELECT id, remaining_held_hp, consumed_hp, released_hp, status FROM billing.point_charge_usage ORDER BY id"""));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    private void assertBalances(Long walletId, long charge, long revenue, long heldCharge, long heldRevenue) {
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(List.of(wallet.getChargeBalanceHp(), wallet.getRevenueBalanceHp(), wallet.getHeldChargeHp(),
                wallet.getHeldRevenueHp())).as("충전·수익·보류 충전·보류 수익").containsExactly(charge, revenue,
                heldCharge, heldRevenue);
    }

    // 금융 불변식: 지갑 캐시 = 원장 합계, 충전 원금 합계 = 충전 HP 가용 잔액, 보류 합계 = 보류 잔액, 배분 남은 보류 합계 = 충전 HP 보류 잔액,
    // 보류별 (최초 - 남은 보류) = 상세 합계, 배분 소진 = 지급·확정 차감 상세의 충전 HP, 배분 반환 = 반환 상세의 충전 HP,
    // 충전 건 지정 상세는 그 충전 건 배분에서만 처리, 확정 차감 작업마다 원장 합계 = -(차감액).
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
                            WHERE d.point_hold_id = h.id AND d.action_type IN ('PAYOUT', 'CAPTURE')) <> (
                            SELECT coalesce(sum(u.consumed_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id)
                        OR (SELECT coalesce(sum(d.charge_amount_hp), 0) FROM billing.point_hold_detail d
                            WHERE d.point_hold_id = h.id AND d.action_type = 'RELEASE') <> (
                            SELECT coalesce(sum(u.released_hp), 0) FROM billing.point_charge_usage u WHERE u.point_hold_id = h.id))""",
                    Integer.class, walletId)).as("보류별 배분·상세 일치").isZero();
        }
        assertThat(jdbcTemplate.queryForList("""
                SELECT l.operation_key FROM billing.point_ledger l
                JOIN billing.point_hold_detail d ON d.idempotency_key = l.operation_key
                WHERE d.action_type = 'CAPTURE'
                GROUP BY l.operation_key, d.charge_amount_hp, d.revenue_amount_hp
                HAVING sum(l.amount_hp) <> -(d.charge_amount_hp + d.revenue_amount_hp)""", String.class))
                .as("확정 차감 원장 합계 = -(차감액)").isEmpty();
    }

    private void failOnLedgerInsert(Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail BEFORE INSERT ON billing.point_ledger"
                + " FOR EACH ROW EXECUTE FUNCTION billing.test_fail()");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing.point_ledger");
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    private static void assertBillingError(Runnable action, BillingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
