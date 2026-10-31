package com.hapbang.billing.app;

import static com.hapbang.billing.fixture.ChargeFixture.paymentKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.PointRefundStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Cancel;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.out.TossPaymentsClient;
import com.hapbang.billing.out.TossResult;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

// 충전 HP 환불(#260)을 실제 PostgreSQL과 가짜 TossPayments HTTP 서버로 검증한다. 실제 TossPayments는 부르지 않는다.
// 모든 시나리오 끝에 정합성(요청액 = 개별 환불 합 = 보류액, 완료액 = 성공 합 = 확정 차감 합 = 환불 원장 합, 반환 합 = 반환 상세 합,
// 충전 건 환불 누계 = 그 결제의 PG 취소 합, 지갑 = 원장)을 함께 확인한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273)
@WithMockUser(roles = "INTERNAL")
class RefundIntegrationTest {

    private static final FakeTossPaymentsServer TOSS = new FakeTossPaymentsServer();
    private static final long CHARGE = 10_000L;
    private static final String CANCEL_KEY =
            "hapbang-refund-cancel-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @DynamicPropertySource
    static void refundProperties(DynamicPropertyRegistry registry) {
        ChargeFixture.register(registry, TOSS);
    }

    @Autowired
    ChargeOrderUseCase chargeOrderUseCase;

    @Autowired
    ChargeConfirmUseCase chargeConfirmUseCase;

    @Autowired
    RefundRequestUseCase refundRequestUseCase;

    @Autowired
    RefundProcessUseCase refundProcessUseCase;

    @Autowired
    RefundProcessing refundProcessing;

    @Autowired
    RefundRecoveryUseCase refundRecoveryUseCase;

    @Autowired
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 환불 복구 정책(UNKNOWN 한도·멱등키 안전 기간) 판단 시각. 실제로 기다리지 않고 timeShift만큼 앞당겨 시간 경계를 검증한다
    @MockitoSpyBean
    RefundClock refundClock;

    private volatile Duration timeShift = Duration.ZERO;

    @BeforeEach
    void setUp() {
        TOSS.reset();
        TOSS.timeoutMillis(1_500L);
        timeShift = Duration.ZERO;
        doAnswer(invocation -> Instant.now().plus(timeShift).truncatedTo(ChronoUnit.MICROS)).when(refundClock).now();
    }

    @AfterAll
    static void tearDown() {
        TOSS.close();
    }

    // ---------------------------------------------------------------------------------------------
    // 정상 환불·FIFO
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전 건 하나의 일부를 환불하면 PG 부분 취소 금액만 확정 차감하고 충전 건은 PARTIALLY_REFUNDED가 된다")
    void refund_singleChargePartial() {
        Long walletId = wallet(1L);
        String order = charge(1L);

        RefundResult result = refund(1L, "key-1", 3_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(result.completedAmount()).isEqualTo(3_000L);
        assertThat(result.items()).extracting(RefundResult.Item::status).containsExactly(PointRefundStatus.SUCCEEDED);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount).containsExactly(3_000L);
        assertThat(TOSS.cancelsOf(order).getFirst().reason())
                .isEqualTo("HP 환불 [" + refundRow(result, 0).get("idempotency_key") + "]");
        assertThat(chargeRow(order)).containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("refunded_amount_hp", 3_000L).containsEntry("remaining_principal_hp", 7_000L);
        assertWallet(walletId, 7_000L, 0L);
        assertConsistent(result.refundRequestId());
        // 원장: 보류(가용 → 보류) 2줄 + 확정 차감(보류 감소, REFUND) 1줄
        assertThat(ledgerAmounts(result.refundRequestId(), "REFUND")).containsExactly(-3_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 999L, 1_234L})
    @DisplayName("1 HP 단위의 어떤 금액이든 환불하고 그 금액만큼 PG 부분 취소한다 (#453)")
    void refund_anyAmountFromOne(long amount) {
        Long walletId = wallet(1L);
        String order = charge(1L);

        RefundResult result = refund(1L, "key-1", amount);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(result.completedAmount()).isEqualTo(amount);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount).containsExactly(amount);
        assertWallet(walletId, CHARGE - amount, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("충전 건 금액 전부를 환불하면 충전 건은 REFUNDED이고 PG 결제는 CANCELED다")
    void refund_fullCharge() {
        Long walletId = wallet(1L);
        String order = charge(1L);

        RefundResult result = refund(1L, "key-1", CHARGE);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(chargeRow(order)).containsEntry("status", "REFUNDED").containsEntry("refunded_amount_hp", CHARGE)
                .containsEntry("remaining_principal_hp", 0L);
        assertThat(TOSS.statusOf(order)).isEqualTo("CANCELED");
        assertWallet(walletId, 0L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("FIFO: 오래된 충전 건부터, 사용·보류된 원금은 빼고 배분해 결제 건마다 순서대로 PG 부분 취소한다")
    void refund_fifoAcrossCharges() {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        String third = charge(1L);
        // 강의 주문 보류가 첫 충전 건 원금 4,000을 쓰고 있다
        holdFacade.hold(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 501L, 4_000L));

        RefundResult result = refund(1L, "key-1", 12_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(result.items()).extracting(RefundResult.Item::chargeId, RefundResult.Item::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(chargeId(first), 6_000L),
                        org.assertj.core.groups.Tuple.tuple(chargeId(second), 6_000L));
        assertThat(TOSS.cancelsOf(first)).extracting(Cancel::amount).containsExactly(6_000L);
        assertThat(TOSS.cancelsOf(second)).extracting(Cancel::amount).containsExactly(6_000L);
        assertThat(TOSS.cancelsOf(third)).isEmpty();
        // 결제 건 순서대로 취소했다
        assertThat(cancelPaths()).containsExactly(cancelPath(first), cancelPath(second));
        assertThat(chargeRow(first)).containsEntry("remaining_principal_hp", 0L).containsEntry("refunded_amount_hp", 6_000L);
        assertThat(chargeRow(second)).containsEntry("remaining_principal_hp", 4_000L);
        assertWallet(walletId, 14_000L, 4_000L);
        assertConsistent(result.refundRequestId());

        // 환불 후에도 강의 보류 반환·지급이 그대로 동작한다(부분 환불 충전 건에 원금 복원)
        holdFacade.release(ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 501L, "CANCEL-501"));
        assertThat(chargeRow(first)).containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("remaining_principal_hp", 4_000L).containsEntry("refunded_amount_hp", 6_000L);
        assertWallet(walletId, 18_000L, 0L);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("환불은 충전 HP만 쓴다: 수익 HP는 환불 가능 금액에 들어가지 않는다")
    void refund_chargeHpOnly() {
        wallet(1L);
        Long sellerWallet = wallet(2L);
        charge(1L);
        charge(2L);
        // 회원 2가 회원 1에게 3,000을 지급받아 수익 HP 3,000 + 충전 HP 10,000
        holdFacade.hold(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 601L, 3_000L));
        holdFacade.payout(PayoutCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 601L, "PAYOUT-601", 2L,
                com.hapbang.shared.billing.domain.SettlementSourceType.LECTURE_ORDER_ITEM, 601L));

        assertBillingError(() -> refund(2L, "key-1", 11_000L), BillingErrorCode.INSUFFICIENT_BALANCE);
        assertThat(refundRequestCount()).isZero();
        assertThat(TOSS.cancelCount()).isZero();

        RefundResult result = refund(2L, "key-2", CHARGE);
        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(walletRepository.findById(sellerWallet).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getRevenueBalanceHp).containsExactly(0L, 3_000L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("PG 취소 멱등키는 개별 환불마다 만든 UUID 저장값이고, PG Idempotency-Key와 취소 사유 표식에 그 저장값을 쓴다")
    void refund_cancelKeyIsStoredUuid() {
        wallet(1L);
        String first = charge(1L);
        String second = charge(1L);

        RefundResult result = refund(1L, "key-1", 12_000L);

        String firstKey = (String) refundRow(result, 0).get("idempotency_key");
        String secondKey = (String) refundRow(result, 1).get("idempotency_key");
        assertThat(List.of(firstKey, secondKey)).doesNotHaveDuplicates().allMatch(key -> key.matches(CANCEL_KEY));
        assertThat(cancelRequests(first)).extracting(FakeTossPaymentsServer.Request::idempotencyKey)
                .containsExactly(firstKey);
        assertThat(cancelRequests(second)).extracting(FakeTossPaymentsServer.Request::idempotencyKey)
                .containsExactly(secondKey);
        assertThat(TOSS.cancelsOf(first)).extracting(Cancel::reason).containsExactly("HP 환불 [" + firstKey + "]");
        assertThat(TOSS.cancelsOf(second)).extracting(Cancel::reason).containsExactly("HP 환불 [" + secondKey + "]");
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("DB 초기화·다른 환경이 같은 요청·충전 ID로 같은 상점에 이미 쓴 ID 기반 키가 있어도, 이번 취소는 그 응답·취소와 섞이지 않는다")
    void refund_cancelKeyIndependentOfDbIds() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        wallet(2L);
        String foreign = charge(2L);
        Long requestId = refundRequestUseCase.create(1L, "key-1", 3_000L);
        // 같은 TossPayments 상점을 쓰는 다른 환경(또는 초기화 전 DB)이 같은 요청 ID·충전 ID로 만든 키로 다른 결제를 취소해 두었다
        String idBasedKey = BillingKeys.REFUND_CANCEL_PREFIX + requestId + "-" + chargeId(order);
        assertThat(tossPaymentsClient.cancel(paymentKey(foreign), idBasedKey, 1_000L,
                RefundProcessUseCase.CANCEL_REASON_PREFIX + "[" + idBasedKey + "]")).isInstanceOf(TossResult.Paid.class);

        RefundResult result = refundProcessUseCase.process(requestId);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        String key = (String) refundRow(result, 0).get("idempotency_key");
        assertThat(key).matches(CANCEL_KEY).isNotEqualTo(idBasedKey);
        assertThat(cancelRequests(order)).extracting(FakeTossPaymentsServer.Request::idempotencyKey).containsExactly(key);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount, Cancel::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3_000L, "HP 환불 [" + key + "]"));
        // 다른 환경의 취소는 그대로 한 건이고 우리 기록에 들어오지 않았다
        assertThat(TOSS.cancelsOf(foreign)).extracting(Cancel::amount).containsExactly(1_000L);
        assertThat(chargeRow(order)).containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("refunded_amount_hp", 3_000L);
        assertThat(chargeRow(foreign)).containsEntry("refunded_amount_hp", 0L);
        assertWallet(walletId, 7_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -1_000L})
    @DisplayName("환불 금액이 1 HP보다 작으면 REFUND_AMOUNT_TOO_SMALL이고 아무것도 남기지 않는다 (#453)")
    void refund_invalidAmount(long amount) {
        Long walletId = wallet(1L);
        charge(1L);

        assertBillingError(() -> refund(1L, "key-1", amount), BillingErrorCode.REFUND_AMOUNT_TOO_SMALL);
        assertThat(refundRequestCount()).isZero();
        assertWallet(walletId, CHARGE, 0L);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재요청은 처음 요청 결과이고 PG를 다시 부르지 않는다. 금액이 다르면 IDEMPOTENCY_KEY_CONFLICT")
    void refund_idempotentReplay() {
        Long walletId = wallet(1L);
        charge(1L);
        RefundResult first = refund(1L, "key-1", 2_000L);

        RefundResult replay = refund(1L, "key-1", 2_000L);

        assertThat(replay.refundRequestId()).isEqualTo(first.refundRequestId());
        assertThat(replay.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelCount()).isEqualTo(1);
        assertBillingError(() -> refund(1L, "key-1", 3_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        // 다른 회원의 같은 키는 다른 요청이다
        wallet(2L);
        charge(2L);
        assertThat(refund(2L, "key-1", 2_000L).refundRequestId()).isNotEqualTo(first.refundRequestId());
        assertWallet(walletId, 8_000L, 0L);
        assertConsistent(first.refundRequestId());
    }

    // ---------------------------------------------------------------------------------------------
    // 실패 확정과 FIFO 중단
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("첫 결제 건 취소가 요청 오류로 거절되면 뒤 결제 건은 PG에 요청하지 않고 전부 반환해 FAILED로 끝난다")
    void refund_firstRejected() {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        TOSS.nextCancel(first, Fault.INVALID_REQUEST);

        RefundResult result = refund(1L, "key-1", 15_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.FAILED);
        assertThat(result.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.FAILED, PointRefundStatus.CANCELED);
        assertThat(result.returnedAmount()).isEqualTo(15_000L);
        assertThat(TOSS.cancelCount()).isEqualTo(1);
        assertThat(TOSS.cancelsOf(second)).isEmpty();
        assertThat(refundRow(result, 0)).containsEntry("failure_reason", "INVALID_REQUEST: 잘못된 요청입니다.");
        assertThat(chargeRow(first)).containsEntry("status", "COMPLETED").containsEntry("remaining_principal_hp", CHARGE);
        assertWallet(walletId, 20_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"403:NOT_CANCELABLE_PAYMENT", "403:EXCEED_MAX_REFUND_DUE", "403:NOT_ALLOWED_PARTIAL_REFUND",
            "400:REFUND_REJECTED", "403:EXCEED_CANCEL_LIMIT", "400:UNDOCUMENTED_CODE"})
    @DisplayName("PG 환급이 불가능한 거절(취소 기간 경과·부분 취소 불가 등, 문서에 없는 코드 포함)은 환불 청구를 남겨 보류를 유지하고 운영자 확인으로 둔다")
    void refund_pgRefundNotPossibleKeepsClaim(String rejection) {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        String[] parts = rejection.split(":");
        TOSS.nextCancelRejection(first, Integer.parseInt(parts[0]), parts[1]);

        RefundResult result = refund(1L, "key-1", 15_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(result.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.MANUAL_REVIEW_REQUIRED, PointRefundStatus.CANCELED);
        assertThat(refundRow(result, 0).get("failure_reason").toString())
                .startsWith("CANCEL_" + parts[0] + ":" + parts[1] + " / PG_REFUND_NOT_POSSIBLE");
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "PG_REFUND_NOT_POSSIBLE");
        assertThat(refundRow(result, 0).get("manual_review_at")).isNotNull();
        // 확정 정책 ①: 보류 유지, 반환·확정 차감 없음
        assertThat(detailSum(holdIdOf(result.refundRequestId()), "CAPTURE")).isZero();
        assertThat(ledgerAmounts(result.refundRequestId(), "REFUND")).isEmpty();
        assertThat(TOSS.cancelsOf(first)).isEmpty();
        assertThat(TOSS.cancelsOf(second)).isEmpty();
        // 첫 결제 건 10,000은 환불 청구로 보류, PG에 요청하지 않은 둘째 건 5,000은 반환
        assertWallet(walletId, 10_000L, CHARGE);
        assertConsistent(result.refundRequestId());
        assertThat(recover()).isZero();
    }

    @Test
    @DisplayName("결과 확인의 재요청이 거절돼도 앞선 요청이 늦게 처리돼 우리 취소가 있으면 반환하지 않고 성공으로 확정한다")
    void recovery_retryRejectedButEarlierCancelLanded() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.UNREACHABLE);
        RefundResult result = refund(1L, "key-1", 4_000L);
        assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN");
        String key = (String) refundRow(result, 0).get("idempotency_key");

        // 앞선 요청이 늦게 PG에 반영됐지만 첫 조회에는 아직 보이지 않고, 같은 키 재요청은 요청 오류로 거절된 경우
        TOSS.cancelDirectly(order, 4_000L, "HP 환불 [" + key + "]");
        TOSS.nextPaymentLookup(order, Fault.HIDE_CANCELS);
        TOSS.nextCancelRejection(order, 400, "INVALID_REQUEST");
        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("같은 표식의 PG 취소가 여러 건(중복 취소)이면 차감하지 않고 운영자 확인으로 보류를 유지한다")
    void refund_duplicateCancelAtPg() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.CANCEL_TWICE);

        RefundResult result = refund(1L, "key-1", 3_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refundRow(result, 0)).containsEntry("status", "MANUAL_REVIEW_REQUIRED")
                .containsEntry("failure_reason", "DUPLICATE_CANCEL:2").containsEntry("pg_refund_key", null)
                .containsEntry("manual_review_reason", "DUPLICATE_CANCEL");
        assertThat(TOSS.cancelsOf(order)).hasSize(2);
        assertThat(ledgerAmounts(result.refundRequestId(), "REFUND")).isEmpty();
        assertWallet(walletId, 7_000L, 3_000L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("처리 중 예외로 상태를 바꾸지 못한 요청도 확인 시각을 갱신해 결과 확인 배치 목록 뒤로 보낸다(기아 방지)")
    void recovery_failingRequestRotates() {
        wallet(1L);
        charge(1L);
        charge(1L);
        Long requestId = refundRequestUseCase.create(1L, "key-1", 15_000L);
        // 요청은 진행 중인데 앞 건이 끝난 실패 상태인 어긋난 기록(next가 REFUND_STATE_INCONSISTENT)
        jdbcTemplate.update("""
                UPDATE billing.point_refund SET status = 'FAILED'
                WHERE id = (SELECT min(id) FROM billing.point_refund WHERE refund_request_id = ?)""", requestId);
        jdbcTemplate.update("UPDATE billing.point_refund_request SET updated_at = now() - interval '1 hour' WHERE id = ?",
                requestId);
        java.sql.Timestamp before = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM billing.point_refund_request WHERE id = ?", java.sql.Timestamp.class, requestId);

        assertThat(refundRecoveryUseCase.recoverStale(Instant.now())).isEqualTo(1);

        java.sql.Timestamp after = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM billing.point_refund_request WHERE id = ?", java.sql.Timestamp.class, requestId);
        assertThat(after).isAfter(before);
        assertThat(refundRecoveryUseCase.recoverStale(Instant.now())).isZero();
        assertThat(TOSS.cancelCount()).isZero();
    }

    @Test
    @DisplayName("앞 결제 건 성공 뒤 실패가 확정되면 성공분은 차감을 유지하고 나머지는 반환해 PARTIALLY_COMPLETED로 끝난다")
    void refund_secondRejected() {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        String third = charge(1L);
        TOSS.nextCancel(second, Fault.INVALID_REQUEST);

        RefundResult result = refund(1L, "key-1", 25_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.PARTIALLY_COMPLETED);
        assertThat(result.items()).extracting(RefundResult.Item::status).containsExactly(PointRefundStatus.SUCCEEDED,
                PointRefundStatus.FAILED, PointRefundStatus.CANCELED);
        assertThat(result.completedAmount()).isEqualTo(CHARGE);
        assertThat(result.returnedAmount()).isEqualTo(15_000L);
        assertThat(TOSS.cancelsOf(first)).extracting(Cancel::amount).containsExactly(CHARGE);
        assertThat(TOSS.cancelsOf(second)).isEmpty();
        assertThat(TOSS.cancelsOf(third)).isEmpty();
        assertThat(cancelPaths()).containsExactly(cancelPath(first), cancelPath(second));
        assertWallet(walletId, 20_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("결제키를 읽을 수 없으면 PG에 요청하지 않고 실패로 끝내 전부 반환한다")
    void refund_unreadablePaymentKey() {
        Long walletId = wallet(1L);
        charge(1L);
        jdbcTemplate.update("UPDATE billing.point_charge SET pg_payment_key = 'v1:corrupted-' || id");

        RefundResult result = refund(1L, "key-1", 5_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.FAILED);
        assertThat(refundRow(result, 0)).containsEntry("failure_reason", "PAYMENT_KEY_UNREADABLE")
                .containsEntry("cancel_request_count", 0).containsEntry("first_cancel_requested_at", null);
        assertThat(TOSS.cancelCount()).isZero();
        assertWallet(walletId, CHARGE, 0L);
        assertConsistent(result.refundRequestId());
    }

    // ---------------------------------------------------------------------------------------------
    // 결과 불명확과 복구
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"UNREACHABLE", "PROVIDER_ERROR", "CONFLICT_PROCESSING", "RATE_LIMITED"})
    @DisplayName("PG가 취소하지 않은 채 결과가 불명확하면 UNKNOWN으로 보류를 유지하고, 결과 확인이 같은 멱등키로 다시 요청해 확정한다")
    void refund_unknownNotCanceledThenRecovered(Fault fault) {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        TOSS.nextCancel(first, fault);

        RefundResult result = refund(1L, "key-1", 15_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.PROCESSING);
        assertThat(result.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.UNKNOWN, PointRefundStatus.PENDING);
        assertThat(result.pendingAmount()).isEqualTo(15_000L);
        // 결과를 모르는 동안 다음 결제 건은 요청하지 않고 보류를 유지한다
        assertThat(TOSS.cancelsOf(first)).isEmpty();
        assertThat(TOSS.cancelsOf(second)).isEmpty();
        assertWallet(walletId, 5_000L, 15_000L);
        assertConsistent(result.refundRequestId());
        // 회원은 PG 취소를 시작한 요청을 취소할 수 없다
        assertBillingError(() -> refundProcessing.cancel(1L, result.refundRequestId()),
                BillingErrorCode.REFUND_NOT_CANCELABLE);

        assertThat(recover()).isEqualTo(1);

        RefundResult recovered = refundProcessing.result(result.refundRequestId());
        assertThat(recovered.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(first)).extracting(Cancel::amount).containsExactly(CHARGE);
        assertThat(TOSS.cancelsOf(second)).extracting(Cancel::amount).containsExactly(5_000L);
        // 같은 결제 건의 재요청은 같은 멱등키다
        String firstKey = (String) refundRow(recovered, 0).get("idempotency_key");
        assertThat(cancelRequests(first)).allSatisfy(request -> assertThat(request.idempotencyKey()).isEqualTo(firstKey));
        assertThat(cancelRequests(first)).hasSizeGreaterThanOrEqualTo(2);
        assertWallet(walletId, 5_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"TIMEOUT_NOT_APPROVED", "SERVER_ERROR"})
    @DisplayName("PG가 취소하지 않은 5xx·시간 초과 응답을 같은 멱등키 응답으로 기억하면, 결과 확인을 반복해도 다시 취소하지 않고 UNKNOWN·보류를 유지한다")
    void refund_unknownCachedErrorKeepsHold(Fault fault) {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, fault);

        RefundResult result = refund(1L, "key-1", 4_000L);
        recover();
        recover();

        RefundResult afterRecovery = refundProcessing.result(result.refundRequestId());
        assertThat(afterRecovery.status()).isEqualTo(PointRefundRequestStatus.PROCESSING);
        // 최초 POST 1 + 결과 확인마다 같은 키 재요청 1, 결과 확인마다 조회 2(재요청 전·후)
        assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN").containsEntry("cancel_request_count", 3)
                .containsEntry("result_check_count", 4);
        assertThat(TOSS.cancelsOf(order)).isEmpty();
        String key = (String) refundRow(result, 0).get("idempotency_key");
        assertThat(cancelRequests(order)).hasSize(3)
                .allSatisfy(request -> assertThat(request.idempotencyKey()).isEqualTo(key));
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(result.refundRequestId());
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"APPROVE_THEN_TIMEOUT", "APPROVE_THEN_SERVER_ERROR"})
    @DisplayName("PG가 취소한 뒤 응답이 유실되면 결제 조회(취소 이력)로 성공을 확인해 한 번만 차감하고 다시 취소하지 않는다")
    void refund_canceledButResponseLost(Fault fault) {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, fault);

        RefundResult result = refund(1L, "key-1", 4_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelCount()).isEqualTo(1);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount).containsExactly(4_000L);
        assertThat(refundRow(result, 0).get("pg_refund_key")).isEqualTo(TOSS.cancelsOf(order).getFirst().transactionKey());
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("PG 취소 후 응답 유실 + 결제 조회 실패면 UNKNOWN으로 남고, 결과 확인이 취소 이력으로 확정한다(재취소 없음)")
    void refund_canceledLookupFailedThenRecovered() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.APPROVE_THEN_SERVER_ERROR);
        TOSS.nextPaymentLookup(order, Fault.SERVER_ERROR);

        RefundResult result = refund(1L, "key-1", 4_000L);
        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.PROCESSING);
        assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN");
        assertWallet(walletId, 6_000L, 4_000L);

        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelCount()).isEqualTo(1);
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("PG 취소 성공 후 DB 반영이 실패하면 PROCESSING으로 남고, 결과 확인이 같은 PG 성공액으로 한 번만 확정 차감한다")
    void refund_dbFailureAfterPgSuccess() {
        Long walletId = wallet(1L);
        String order = charge(1L);

        RefundResult result = failOnRefundLedgerInsert(() -> refund(1L, "key-1", 4_000L));

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.PROCESSING);
        assertThat(refundRow(result, 0)).containsEntry("status", "PROCESSING");
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 6_000L, 4_000L);

        recover();
        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelCount()).isEqualTo(1);
        assertThat(ledgerAmounts(result.refundRequestId(), "REFUND")).containsExactly(-4_000L);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("이미 취소됨 거절인데 취소 이력에 우리 취소가 없으면 운영자 확인으로 보류를 유지하고 남은 결제 건은 반환한다")
    void refund_alreadyCanceledWithoutOurCancel() {
        Long walletId = wallet(1L);
        String first = charge(1L);
        charge(1L);
        TOSS.nextCancel(first, Fault.ALREADY_CANCELED);

        RefundResult result = refund(1L, "key-1", 15_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(result.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.MANUAL_REVIEW_REQUIRED, PointRefundStatus.CANCELED);
        assertThat(refundRow(result, 0).get("failure_reason").toString())
                .startsWith("CANCEL_400:ALREADY_CANCELED_PAYMENT / NO_MATCHING_CANCEL");
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "NO_MATCHING_CANCEL");
        assertWallet(walletId, 10_000L, CHARGE);
        assertConsistent(result.refundRequestId());
        // 운영자 확인 요청은 결과 확인 배치가 다루지 않는다
        assertThat(recover()).isZero();
    }

    @Test
    @DisplayName("이미 취소됨 거절이어도 취소 이력에 우리 취소가 있으면 성공으로 확정한다")
    void refund_alreadyCanceledByOurEarlierCancel() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.APPROVE_THEN_SERVER_ERROR);
        TOSS.nextPaymentLookup(order, Fault.SERVER_ERROR);
        RefundResult result = refund(1L, "key-1", CHARGE);
        assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN");

        // 결과 확인: 조회에 취소 이력이 아직 보이지 않아 같은 멱등키로 다시 요청했고, (멱등키 기록 없이) 이미 취소됨으로 거절된 뒤
        // 다시 조회하니 우리 취소가 있다
        TOSS.nextPaymentLookup(order, Fault.HIDE_CANCELS);
        TOSS.nextCancel(order, Fault.ALREADY_CANCELED);
        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 0L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("PG가 요청과 다른 금액을 취소하면 차감하지 않고 운영자 확인으로 보류를 유지한다")
    void refund_canceledAmountMismatch() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.CANCEL_DIFFERENT_AMOUNT);

        RefundResult result = refund(1L, "key-1", 4_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refundRow(result, 0)).containsEntry("failure_reason", "CANCEL_AMOUNT_MISMATCH:2000")
                .containsEntry("manual_review_reason", "CANCEL_AMOUNT_MISMATCH");
        assertThat(ledgerAmounts(result.refundRequestId(), "REFUND")).isEmpty();
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("결제 조회가 404(승인된 결제 없음)면 기록과 맞지 않으므로 운영자 확인으로 보류를 유지한다")
    void refund_lookupNotFound() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.SERVER_ERROR);
        TOSS.expireSession(order);

        RefundResult result = refund(1L, "key-1", 4_000L);

        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refundRow(result, 0).get("failure_reason").toString()).contains("LOOKUP_404:NOT_FOUND_PAYMENT");
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "PAYMENT_NOT_FOUND");
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(result.refundRequestId());
    }

    // ---------------------------------------------------------------------------------------------
    // 회원 취소·동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PG 취소 전(REQUESTED) 요청은 회원이 취소할 수 있고 보류 HP를 모두 반환한다. 다른 회원은 찾을 수 없다")
    void cancel_beforePgCall() {
        Long walletId = wallet(1L);
        charge(1L);
        charge(1L);
        wallet(2L);
        Long requestId = refundRequestUseCase.create(1L, "key-1", 15_000L);
        assertWallet(walletId, 5_000L, 15_000L);

        assertBillingError(() -> refundProcessing.cancel(2L, requestId), BillingErrorCode.REFUND_NOT_FOUND);
        RefundResult canceled = refundProcessing.cancel(1L, requestId);

        assertThat(canceled.status()).isEqualTo(PointRefundRequestStatus.CANCELED);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(canceled.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.CANCELED, PointRefundStatus.CANCELED);
        assertThat(TOSS.cancelCount()).isZero();
        assertWallet(walletId, 20_000L, 0L);
        assertConsistent(requestId);
        assertBillingError(() -> refundProcessing.cancel(1L, requestId), BillingErrorCode.REFUND_NOT_CANCELABLE);
        // 취소된 요청은 처리·결과 확인해도 PG를 부르지 않는다
        assertThat(refundProcessUseCase.process(requestId).status()).isEqualTo(PointRefundRequestStatus.CANCELED);
        assertThat(TOSS.cancelCount()).isZero();
    }

    @Test
    @DisplayName("접수 후 처리 전에 멈춘 요청은 결과 확인 배치가 이어서 처리한다")
    void recovery_resumesRequested() {
        Long walletId = wallet(1L);
        charge(1L);
        Long requestId = refundRequestUseCase.create(1L, "key-1", 3_000L);

        recover();

        assertThat(refundProcessing.result(requestId).status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertWallet(walletId, 7_000L, 0L);
        assertConsistent(requestId);
    }

    @Test
    @DisplayName("회원 취소와 PG 취소 처리가 동시에 오면 둘 중 하나만 이긴다(취소면 PG 호출 0, 처리면 취소 거절)")
    void cancel_racesWithProcessing() throws Exception {
        Long walletId = wallet(1L);
        charge(1L);
        TOSS.cancelDelay(200L);
        for (int round = 0; round < 6; round++) {
            long before = TOSS.cancelCount();
            Long requestId = refundRequestUseCase.create(1L, "race-" + round, 1_000L);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<RefundResult> process = executor.submit(() -> {
                    awaitLatch(start);
                    return refundProcessUseCase.process(requestId);
                });
                Future<Object> cancel = executor.submit(() -> {
                    awaitLatch(start);
                    try {
                        return refundProcessing.cancel(1L, requestId);
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                });
                start.countDown();
                process.get(30, TimeUnit.SECONDS);
                Object cancelResult = cancel.get(30, TimeUnit.SECONDS);
                RefundResult result = refundProcessing.result(requestId);
                if (cancelResult instanceof RefundResult) {
                    assertThat(result.status()).isEqualTo(PointRefundRequestStatus.CANCELED);
                    assertThat(TOSS.cancelCount()).isEqualTo(before);
                } else {
                    assertThat(cancelResult).isEqualTo(BillingErrorCode.REFUND_NOT_CANCELABLE);
                    assertThat(result.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
                    assertThat(TOSS.cancelCount()).isEqualTo(before + 1);
                }
            }
            assertConsistent(requestId);
        }
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("API 처리와 결과 확인 배치가 같은 요청을 동시에 처리해도 결제 건마다 PG 취소는 한 번, 확정 차감도 한 번이다")
    void processing_concurrentWithRecovery() throws Exception {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        TOSS.cancelDelay(300L);
        Long requestId = refundRequestUseCase.create(1L, "key-1", 15_000L);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<RefundResult>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                awaitLatch(start);
                return refundProcessUseCase.process(requestId);
            }));
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    awaitLatch(start);
                    return refundProcessUseCase.recover(requestId, Instant.now().plusSeconds(120));
                }));
            }
            start.countDown();
            for (Future<RefundResult> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        TOSS.cancelDelay(0L);
        recover();
        recover();

        assertThat(refundProcessing.result(requestId).status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(first)).extracting(Cancel::amount).containsExactly(CHARGE);
        assertThat(TOSS.cancelsOf(second)).extracting(Cancel::amount).containsExactly(5_000L);
        assertThat(captureDetailCount(requestId)).isEqualTo(2);
        assertWallet(walletId, 5_000L, 0L);
        assertConsistent(requestId);
    }

    @Test
    @DisplayName("같은 충전 HP로 동시에 여러 환불을 요청해도 가용 충전 HP를 넘게 예약·취소하지 않는다")
    void concurrentRequests_noOverRefund() throws Exception {
        Long walletId = wallet(1L);
        String order = charge(1L);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String key = "concurrent-" + i;
                futures.add(executor.submit(() -> {
                    awaitLatch(start);
                    try {
                        return refund(1L, key, 6_000L);
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(outcomes).filteredOn(RefundResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BillingErrorCode.INSUFFICIENT_BALANCE::equals).hasSize(2);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount).containsExactly(6_000L);
        assertThat(chargeRow(order)).containsEntry("refunded_amount_hp", 6_000L);
        assertWallet(walletId, 4_000L, 0L);
        for (Long requestId : refundRequestIds()) {
            assertConsistent(requestId);
        }
    }

    @Test
    @DisplayName("결과를 모르는 요청이 있어도 남은 가용 충전 HP 안에서 추가 환불을 요청할 수 있고, 같은 결제 건 원금을 겹쳐 예약하지 않는다")
    void additionalRequestWhileUnknown() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.UNREACHABLE);
        RefundResult unknown = refund(1L, "key-1", 6_000L);
        assertThat(unknown.status()).isEqualTo(PointRefundRequestStatus.PROCESSING);

        assertBillingError(() -> refund(1L, "key-2", 5_000L), BillingErrorCode.INSUFFICIENT_BALANCE);
        RefundResult additional = refund(1L, "key-3", 4_000L);
        assertThat(additional.status()).isEqualTo(PointRefundRequestStatus.COMPLETED);

        recover();

        assertThat(refundProcessing.result(unknown.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(order)).extracting(Cancel::amount).containsExactlyInAnyOrder(4_000L, 6_000L);
        assertThat(TOSS.statusOf(order)).isEqualTo("CANCELED");
        assertThat(chargeRow(order)).containsEntry("status", "REFUNDED").containsEntry("refunded_amount_hp", CHARGE);
        assertWallet(walletId, 0L, 0L);
        assertConsistent(unknown.refundRequestId());
        assertConsistent(additional.refundRequestId());
    }

    // ---------------------------------------------------------------------------------------------
    // 확정 정책 ② UNKNOWN 자동 복구 한도(24시간·재확인 12회), 검토 4 멱등키 안전 기간
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("UNKNOWN 후 24시간 미만·재확인 12회 미만이면 자동 복구한다(최초 UNKNOWN 시각은 바뀌지 않는다)")
    void unknown_withinLimits_recovers() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.UNREACHABLE);
        RefundResult result = refund(1L, "key-1", 4_000L);
        Object unknownSince = refundRow(result, 0).get("unknown_since");
        assertThat(unknownSince).isNotNull();
        assertThat(refundRow(result, 0)).containsEntry("result_check_count", 0).containsEntry("cancel_request_count", 1);

        timeShift = Duration.ofHours(24).minusSeconds(1);
        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(refundRow(result, 0)).containsEntry("unknown_since", unknownSince)
                .containsEntry("result_check_count", 1).containsEntry("cancel_request_count", 2);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("UNKNOWN 후 24시간이 되면(경계 포함) PG를 부르지 않고 운영자 확인으로 바꾸며 보류를 유지하고 뒤 건만 반환한다")
    void unknown_timeLimit() {
        Long walletId = wallet(1L);
        String first = charge(1L);
        String second = charge(1L);
        TOSS.nextCancel(first, Fault.UNREACHABLE);
        RefundResult result = refund(1L, "key-1", 15_000L);
        long requestsBefore = TOSS.requests().size();

        timeShift = Duration.ofHours(24);
        recover();

        RefundResult reviewed = refundProcessing.result(result.refundRequestId());
        assertThat(reviewed.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reviewed.items()).extracting(RefundResult.Item::status)
                .containsExactly(PointRefundStatus.MANUAL_REVIEW_REQUIRED, PointRefundStatus.CANCELED);
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "UNKNOWN_TIME_LIMIT")
                .containsEntry("result_check_count", 0);
        assertThat(refundRow(result, 0).get("failure_reason").toString()).contains("UNKNOWN_TIME_LIMIT");
        // 한도 도달 판단에서 PG를 부르지 않았다
        assertThat(TOSS.requests()).hasSize((int) requestsBefore);
        assertThat(TOSS.cancelsOf(first)).isEmpty();
        assertThat(TOSS.cancelsOf(second)).isEmpty();
        // 첫 건 10,000 보류 유지(자동 반환 없음), 요청하지 않은 둘째 건 5,000만 반환
        assertWallet(walletId, 10_000L, CHARGE);
        assertConsistent(result.refundRequestId());
        // 운영자 확인 이후 배치는 다시 처리하지 않는다
        timeShift = Duration.ofDays(2);
        assertThat(recover()).isZero();
        assertThat(TOSS.requests()).hasSize((int) requestsBefore);
    }

    @Test
    @DisplayName("PG 조회 실패도 재확인 횟수에 들어가고, 재확인 12회에 도달하면 운영자 확인으로 바꾸며 보류를 유지한다")
    void unknown_checkLimit_includesFailedLookups() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.UNREACHABLE);
        RefundResult result = refund(1L, "key-1", 4_000L);
        for (int i = 0; i < 12; i++) {
            TOSS.nextPaymentLookup(order, Fault.SERVER_ERROR);
        }

        for (int i = 1; i <= 11; i++) {
            recover();
            assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN").containsEntry("result_check_count", i);
        }
        recover();

        assertThat(refundRow(result, 0)).containsEntry("status", "MANUAL_REVIEW_REQUIRED")
                .containsEntry("manual_review_reason", "UNKNOWN_CHECK_LIMIT").containsEntry("result_check_count", 12)
                // 조회가 모두 실패해 같은 키 재요청은 한 번도 하지 않았다
                .containsEntry("cancel_request_count", 1);
        assertThat(refundRow(result, 0).get("failure_reason").toString()).contains("UNKNOWN_CHECK_LIMIT");
        assertThat(TOSS.cancelsOf(order)).isEmpty();
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(result.refundRequestId());
        assertThat(recover()).isZero();
    }

    @Test
    @DisplayName("재확인 12회째 조회에서 취소 성공이 확인되면 횟수와 관계없이 확정 차감한다")
    void unknown_successOnLastCheckCaptures() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        TOSS.nextCancel(order, Fault.APPROVE_THEN_SERVER_ERROR);
        TOSS.nextPaymentLookup(order, Fault.SERVER_ERROR);
        RefundResult result = refund(1L, "key-1", 4_000L);
        assertThat(refundRow(result, 0)).containsEntry("status", "UNKNOWN");
        jdbcTemplate.update("UPDATE billing.point_refund SET result_check_count = 11 WHERE refund_request_id = ?",
                result.refundRequestId());

        timeShift = Duration.ofHours(23);
        recover();

        assertThat(refundProcessing.result(result.refundRequestId()).status())
                .isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(refundRow(result, 0)).containsEntry("result_check_count", 12).containsEntry("cancel_request_count", 1)
                .containsEntry("manual_review_reason", null);
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(result.refundRequestId());
    }

    @Test
    @DisplayName("멱등키 안전 기간(최초 요청 + 14일, 경계 포함)이 지나면 같은 키로 다시 POST하지 않고 보류를 유지한 채 운영자 확인으로 둔다")
    void idempotencyWindow_blocksRetryAtBoundary() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        Long requestId = claimWithoutPgCall(1L, "key-1", 4_000L);
        long cancelsBefore = TOSS.cancelCount();

        timeShift = RefundProcessing.IDEMPOTENCY_KEY_SAFE_WINDOW;
        recover();

        RefundResult result = refundProcessing.result(requestId);
        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "IDEMPOTENCY_KEY_EXPIRED")
                .containsEntry("cancel_request_count", 1).containsEntry("result_check_count", 1);
        assertThat(TOSS.cancelCount()).isEqualTo(cancelsBefore);
        assertThat(TOSS.cancelsOf(order)).isEmpty();
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(requestId);
    }

    @Test
    @DisplayName("멱등키 안전 기간 직전에는 같은 키로 다시 요청해 확정한다")
    void idempotencyWindow_retryBeforeBoundary() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        Long requestId = claimWithoutPgCall(1L, "key-1", 4_000L);

        timeShift = RefundProcessing.IDEMPOTENCY_KEY_SAFE_WINDOW.minusSeconds(1);
        recover();

        assertThat(refundProcessing.result(requestId).status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelsOf(order)).hasSize(1);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(requestId);
    }

    @Test
    @DisplayName("멱등키 안전 기간이 지나도 취소 이력에서 우리 취소가 확인되면 POST 없이 확정 차감한다")
    void idempotencyWindow_expiredButCanceledCaptures() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        Long requestId = claimWithoutPgCall(1L, "key-1", 4_000L);
        String key = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM billing.point_refund WHERE refund_request_id = ?", String.class, requestId);
        // 처음 요청은 PG에 반영됐는데 응답 전에 서버가 멈춘 경우
        TOSS.cancelDirectly(order, 4_000L, "HP 환불 [" + key + "]");
        long cancelsBefore = TOSS.cancelCount();

        timeShift = Duration.ofDays(15);
        recover();

        assertThat(refundProcessing.result(requestId).status()).isEqualTo(PointRefundRequestStatus.COMPLETED);
        assertThat(TOSS.cancelCount()).isEqualTo(cancelsBefore);
        assertWallet(walletId, 6_000L, 0L);
        assertConsistent(requestId);
    }

    @Test
    @DisplayName("멱등키 안전 기간이 지난 뒤 조회도 실패하면 보류를 유지한 채 운영자 확인으로 둔다")
    void idempotencyWindow_expiredAndLookupFails() {
        Long walletId = wallet(1L);
        String order = charge(1L);
        Long requestId = claimWithoutPgCall(1L, "key-1", 4_000L);
        TOSS.nextPaymentLookup(order, Fault.SERVER_ERROR);

        timeShift = Duration.ofDays(15);
        recover();

        RefundResult result = refundProcessing.result(requestId);
        assertThat(result.status()).isEqualTo(PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(refundRow(result, 0)).containsEntry("manual_review_reason", "IDEMPOTENCY_KEY_EXPIRED");
        assertThat(TOSS.cancelCount()).isZero();
        assertWallet(walletId, 6_000L, 4_000L);
        assertConsistent(requestId);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // 접수 후 첫 PG 취소를 PROCESSING으로 커밋한 직후 서버가 멈춰 PG에 요청하지 못한 상태를 만든다(최초 요청 시각 = 현재 RefundClock)
    private Long claimWithoutPgCall(Long userId, String key, long amount) {
        Long requestId = refundRequestUseCase.create(userId, key, amount);
        assertThat(refundProcessing.next(requestId, null)).isInstanceOf(RefundProcessing.Step.Call.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM billing.point_refund WHERE refund_request_id = ? ORDER BY id LIMIT 1", String.class,
                requestId)).isEqualTo("PROCESSING");
        return requestId;
    }

    private Long holdIdOf(Long requestId) {
        return jdbcTemplate.queryForObject("SELECT point_hold_id FROM billing.point_refund_request WHERE id = ?",
                Long.class, requestId);
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 주문 → 가짜 PG 인증 → 승인으로 충전을 완료하고 주문 ID를 돌려준다.
    private String charge(Long userId) {
        ChargeResult order = chargeOrderUseCase.create(userId, CHARGE);
        TOSS.authorize(order.orderId(), paymentKey(order.orderId()), CHARGE);
        chargeConfirmUseCase.confirm(userId, paymentKey(order.orderId()), order.orderId(), CHARGE);
        return order.orderId();
    }

    private RefundResult refund(Long userId, String key, long amount) {
        return refundProcessUseCase.process(refundRequestUseCase.create(userId, key, amount));
    }

    private int recover() {
        return refundRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));
    }

    private Long chargeId(String orderId) {
        return (Long) chargeRow(orderId).get("id");
    }

    private Map<String, Object> chargeRow(String orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.point_charge WHERE pg_order_id = ?", orderId);
    }

    private Map<String, Object> refundRow(RefundResult result, int index) {
        return jdbcTemplate.queryForList("SELECT * FROM billing.point_refund WHERE refund_request_id = ? ORDER BY id",
                result.refundRequestId()).get(index);
    }

    private int refundRequestCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class);
    }

    private List<Long> refundRequestIds() {
        return jdbcTemplate.queryForList("SELECT id FROM billing.point_refund_request ORDER BY id", Long.class);
    }

    private int captureDetailCount(Long requestId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_hold_detail d
                JOIN billing.point_refund_request r ON r.point_hold_id = d.point_hold_id
                WHERE r.id = ? AND d.action_type = 'CAPTURE'""", Integer.class, requestId);
    }

    private List<Long> ledgerAmounts(Long requestId, String transactionType) {
        return jdbcTemplate.queryForList("""
                SELECT amount_hp FROM billing.point_ledger
                WHERE reference_type = 'POINT_REFUND_REQUEST' AND reference_id = ? AND transaction_type = ?
                ORDER BY id""", Long.class, requestId, transactionType);
    }

    private String cancelPath(String orderId) {
        return "/v1/payments/" + paymentKey(orderId) + "/cancel";
    }

    private List<String> cancelPaths() {
        return TOSS.requests().stream().filter(request -> request.path().endsWith("/cancel"))
                .map(FakeTossPaymentsServer.Request::path).toList();
    }

    private List<FakeTossPaymentsServer.Request> cancelRequests(String orderId) {
        return TOSS.requests().stream().filter(request -> request.path().equals(cancelPath(orderId))).toList();
    }

    private void assertWallet(Long walletId, long chargeBalanceHp, long heldChargeHp) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getHeldChargeHp)
                .containsExactly(chargeBalanceHp, heldChargeHp);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // 환불 요청 하나의 금액 정합성
    private void assertConsistent(Long requestId) {
        Map<String, Object> request = jdbcTemplate.queryForMap(
                "SELECT * FROM billing.point_refund_request WHERE id = ?", requestId);
        List<Map<String, Object>> refunds = jdbcTemplate.queryForList(
                "SELECT * FROM billing.point_refund WHERE refund_request_id = ? ORDER BY id", requestId);
        Map<String, Object> hold = jdbcTemplate.queryForMap("SELECT * FROM billing.point_hold WHERE id = ?",
                request.get("point_hold_id"));
        long requested = (Long) request.get("requested_amount_hp");
        long succeeded = sum(refunds, "SUCCEEDED");
        long returned = sum(refunds, "FAILED") + sum(refunds, "CANCELED");
        long held = sum(refunds, "PENDING") + sum(refunds, "PROCESSING") + sum(refunds, "UNKNOWN")
                + sum(refunds, "MANUAL_REVIEW_REQUIRED");

        // 1) 요청액 = 개별 환불 합 = 보류액(충전 HP만)
        assertThat(succeeded + returned + held).isEqualTo(requested);
        assertThat(hold).containsEntry("hold_type", "CHARGE_ONLY").containsEntry("original_charge_hp", requested)
                .containsEntry("original_revenue_hp", 0L).containsEntry("held_charge_hp", held);
        // 2) 완료액 = PG 성공 합 = 확정 차감 상세 합 = 환불 원장(보류 감소) 합
        assertThat(request).containsEntry("completed_amount_hp", succeeded);
        assertThat(detailSum((Long) hold.get("id"), "CAPTURE")).isEqualTo(succeeded);
        assertThat(ledgerAmounts(requestId, "REFUND").stream().mapToLong(Long::longValue).sum()).isEqualTo(-succeeded);
        // 운영자 확인 건은 원인·시각이 남고, PG 요청을 보낸 건은 최초 요청 시각이 있다
        for (Map<String, Object> refund : refunds) {
            if ("MANUAL_REVIEW_REQUIRED".equals(refund.get("status"))) {
                assertThat(refund.get("manual_review_reason")).isNotNull();
                assertThat(refund.get("manual_review_at")).isNotNull();
            }
            if ((Integer) refund.get("cancel_request_count") > 0) {
                assertThat(refund.get("first_cancel_requested_at")).isNotNull();
            }
            if ("CANCELED".equals(refund.get("status"))) {
                assertThat(refund.get("cancel_request_count")).isEqualTo(0);
            }
        }
        // 3) 반환 합 = 반환 상세 합
        assertThat(detailSum((Long) hold.get("id"), "RELEASE")).isEqualTo(returned);
        // 4) 성공 건마다 PG 취소 이력에 우리 취소가 정확히 한 건, 같은 금액이다(이중 환불 없음)
        for (Map<String, Object> refund : refunds) {
            String orderId = jdbcTemplate.queryForObject("SELECT pg_order_id FROM billing.point_charge WHERE id = ?",
                    String.class, refund.get("charge_id"));
            String marker = "[" + refund.get("idempotency_key") + "]";
            List<Cancel> ours = TOSS.cancelsOf(orderId).stream().filter(cancel -> cancel.reason().contains(marker))
                    .toList();
            if ("SUCCEEDED".equals(refund.get("status"))) {
                assertThat(ours).hasSize(1);
                assertThat(ours.getFirst().amount()).isEqualTo(refund.get("refund_amount_hp"));
                assertThat(refund.get("pg_refund_key")).isEqualTo(ours.getFirst().transactionKey());
            } else if (!"MANUAL_REVIEW_REQUIRED".equals(refund.get("status"))) {
                assertThat(ours).isEmpty();
            }
        }
        // 5) 충전 건 환불 누계 = 그 결제의 PG 취소 합 = 그 충전 건의 성공 환불 합(모든 요청), 가용 원금 + 누계 ≤ 충전액
        for (Map<String, Object> charge : jdbcTemplate.queryForList("SELECT * FROM billing.point_charge")) {
            if (!List.of("COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED").contains(charge.get("status"))) {
                continue;
            }
            Long refundedBySuccess = jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(refund_amount_hp), 0) FROM billing.point_refund
                    WHERE charge_id = ? AND status = 'SUCCEEDED'""", Long.class, charge.get("id"));
            assertThat(charge.get("refunded_amount_hp")).isEqualTo(refundedBySuccess);
            long canceledAtPg = TOSS.cancelsOf((String) charge.get("pg_order_id")).stream().mapToLong(Cancel::amount)
                    .sum();
            // 운영자 확인 건이 있는 결제는 PG와 기록이 일부러 다르게 남는다(차감하지 않음)
            Integer underReview = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM billing.point_refund
                    WHERE charge_id = ? AND status = 'MANUAL_REVIEW_REQUIRED'""", Integer.class, charge.get("id"));
            if (underReview == 0 && TOSS.cancelsOf((String) charge.get("pg_order_id")).stream()
                    .allMatch(cancel -> cancel.reason().contains("[hapbang-refund-cancel-")
                            && cancel.amount() == refundAmountOf(cancel))) {
                assertThat(canceledAtPg).isEqualTo(refundedBySuccess);
            }
        }
    }

    // 취소 이력 한 건의 표식에 해당하는 개별 환불의 금액(표식이 없으면 -1)
    private long refundAmountOf(Cancel cancel) {
        String reason = cancel.reason();
        int start = reason.indexOf('[');
        int end = reason.indexOf(']');
        if (start < 0 || end < 0) {
            return -1L;
        }
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT refund_amount_hp FROM billing.point_refund WHERE idempotency_key = ?", Long.class,
                reason.substring(start + 1, end));
        return amounts.isEmpty() ? -1L : amounts.getFirst();
    }

    private long detailSum(Long holdId, String actionType) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(charge_amount_hp + revenue_amount_hp), 0) FROM billing.point_hold_detail
                WHERE point_hold_id = ? AND action_type = ?""", Long.class, holdId, actionType);
    }

    private static long sum(List<Map<String, Object>> refunds, String status) {
        return refunds.stream().filter(refund -> status.equals(refund.get("status")))
                .mapToLong(refund -> (Long) refund.get("refund_amount_hp")).sum();
    }

    // 환불 원장(확정 차감) INSERT에 실패 트리거를 잠시 걸고 동작을 실행한 뒤 지운다(PG 취소 성공 후 DB 반영 실패 재현).
    private <T> T failOnRefundLedgerInsert(java.util.function.Supplier<T> action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail_refund() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.transaction_type = 'REFUND' THEN
                        RAISE EXCEPTION 'test failure';
                    END IF;
                    RETURN NEW;
                END;
                $$""");
        jdbcTemplate.execute("CREATE TRIGGER trg_test_fail_refund BEFORE INSERT ON billing.point_ledger"
                + " FOR EACH ROW EXECUTE FUNCTION billing.test_fail_refund()");
        try {
            return action.get();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail_refund ON billing.point_ledger");
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail_refund()");
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertBillingError(Runnable action, BillingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
