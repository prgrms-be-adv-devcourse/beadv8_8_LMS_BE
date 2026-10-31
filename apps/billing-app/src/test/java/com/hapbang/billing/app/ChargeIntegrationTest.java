package com.hapbang.billing.app;

import static com.hapbang.billing.fixture.ChargeFixture.paymentKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.TossPayment;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

// TossPayments 테스트 결제 충전(#224)을 실제 PostgreSQL과 가짜 TossPayments HTTP 서버로 검증한다.
// 실제 TossPayments 서버는 부르지 않는다. PG 응답(승인·거절·5xx·시간 초과·응답 유실)은 FakeTossPaymentsServer가 재현한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273)
@WithMockUser(roles = "INTERNAL")
class ChargeIntegrationTest {

    private static final FakeTossPaymentsServer TOSS = new FakeTossPaymentsServer();
    private static final long AMOUNT = 10_000L;

    @DynamicPropertySource
    static void chargeProperties(DynamicPropertyRegistry registry) {
        ChargeFixture.register(registry, TOSS);
    }

    @Autowired
    ChargeOrderUseCase chargeOrderUseCase;

    @Autowired
    ChargeConfirmUseCase chargeConfirmUseCase;

    @Autowired
    ChargeRecoveryUseCase chargeRecoveryUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    PaymentKeyCipher paymentKeyCipher;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ChargeProcessing chargeProcessing;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        TOSS.reset();
        TOSS.timeoutMillis(1_500L);
    }

    @AfterAll
    static void tearDown() {
        TOSS.close();
    }

    // ---------------------------------------------------------------------------------------------
    // 충전 주문
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전 주문은 서버가 발급한 주문 ID·금액으로 PENDING 저장되고 승인 전이라 HP·원장·가용 원금은 바뀌지 않는다")
    void createOrder() {
        Long walletId = wallet(1L);

        ChargeResult order = chargeOrderUseCase.create(1L, AMOUNT);

        assertThat(order.orderId()).matches("HPCHG-[0-9a-f]{32}");
        assertThat(order.amount()).isEqualTo(AMOUNT);
        assertThat(order.status()).isEqualTo(PointChargeStatus.PENDING);
        assertThat(charge(order.orderId())).containsEntry("wallet_id", walletId)
                .containsEntry("amount_hp", AMOUNT).containsEntry("payment_amount_krw", AMOUNT)
                .containsEntry("remaining_principal_hp", 0L).containsEntry("status", "PENDING")
                .containsEntry("pg_provider", "TOSS_PAYMENTS").containsEntry("pg_payment_key", null);
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
        assertThat(chargeOrderUseCase.create(1L, AMOUNT).orderId()).isNotEqualTo(order.orderId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1_000L, 999L, 1_500L, 10_500L})
    @DisplayName("충전 금액이 1,000 미만이거나 1,000 단위가 아니면 CHARGE_AMOUNT_INVALID로 거절하고 주문을 만들지 않는다")
    void createOrder_invalidAmount(long amount) {
        wallet(1L);

        assertBillingError(() -> chargeOrderUseCase.create(1L, amount), BillingErrorCode.CHARGE_AMOUNT_INVALID);
        assertThat(chargeCount()).isZero();
    }

    @Test
    @DisplayName("지갑이 없는 회원은 주문·승인 모두 WALLET_NOT_FOUND이고 지갑을 만들지 않는다")
    void walletNotFound() {
        wallet(1L);
        ChargeResult order = order(1L);

        assertBillingError(() -> chargeOrderUseCase.create(2L, AMOUNT), BillingErrorCode.WALLET_NOT_FOUND);
        assertBillingError(() -> confirm(2L, order), BillingErrorCode.WALLET_NOT_FOUND);
        assertThat(walletRepository.findByUserId(2L)).isEmpty();
        assertThat(TOSS.confirmCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 승인 전 검증
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("다른 회원의 충전 주문은 승인할 수 없고(CHARGE_NOT_FOUND) PG를 부르지 않으며 주문은 PENDING 그대로다")
    void confirm_otherMembersOrder() {
        wallet(1L);
        wallet(2L);
        ChargeResult order = order(1L);

        assertBillingError(() -> confirm(2L, order), BillingErrorCode.CHARGE_NOT_FOUND);
        assertBillingError(() -> chargeConfirmUseCase.confirm(1L, "tgen_x", "HPCHG-missing-order", AMOUNT),
                BillingErrorCode.CHARGE_NOT_FOUND);
        assertThat(TOSS.confirmCount()).isZero();
        assertThat(charge(order.orderId())).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("승인 요청 금액이 저장한 주문 금액과 다르면 CHARGE_AMOUNT_MISMATCH로 거절하고 PG를 부르지 않는다")
    void confirm_amountMismatch() {
        wallet(1L);
        ChargeResult order = order(1L);

        assertBillingError(() -> chargeConfirmUseCase.confirm(1L, paymentKey(order.orderId()), order.orderId(), 1_000L),
                BillingErrorCode.CHARGE_AMOUNT_MISMATCH);
        assertThat(TOSS.confirmCount()).isZero();
        assertThat(charge(order.orderId())).containsEntry("status", "PENDING").containsEntry("pg_payment_key", null);
    }

    // ---------------------------------------------------------------------------------------------
    // 정상 승인
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PG 승인이 성공하면 COMPLETED·가용 원금 = 충전액·충전 HP 가용 증가·CHARGE 원장 1줄을 한 번에 기록하고 원장 대사가 맞는다")
    void confirm_completes() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);

        ChargeResult result = confirm(1L, order);

        assertThat(result.status()).isEqualTo(PointChargeStatus.COMPLETED);
        assertThat(result.completedAt()).isNotNull();
        Map<String, Object> charge = charge(order.orderId());
        assertThat(charge).containsEntry("status", "COMPLETED").containsEntry("remaining_principal_hp", AMOUNT)
                .containsEntry("failure_reason", null);
        assertWallet(walletId, AMOUNT);
        Long chargeId = (Long) charge.get("id");
        assertThat(ledgerLines("CHARGE:" + chargeId))
                .containsExactly(walletId + " CHARGE AVAILABLE " + AMOUNT + " CHARGE POINT_CHARGE " + chargeId);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        // 충전 원금 합계 = 지갑 충전 HP (보류 배분 불변식)
        assertThat(jdbcTemplate.queryForObject("SELECT sum(remaining_principal_hp) FROM billing.point_charge "
                + "WHERE wallet_id = ? AND status = 'COMPLETED'", Long.class, walletId)).isEqualTo(AMOUNT);
        // TossPayments에는 시크릿 키 Basic 인증과 주문별 멱등키로 한 번만 승인을 요청했다
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertThat(TOSS.requests().getFirst().idempotencyKey()).isEqualTo("hapbang-charge-confirm-" + order.orderId());
        assertThat(TOSS.requests().getFirst().authorization()).startsWith("Basic ");
    }

    @Test
    @DisplayName("결제키는 평문으로 저장하지 않는다: 암호문(v1:)과 HMAC만 저장하고 암호문을 복호화하면 원래 결제키다")
    void confirm_paymentKeyProtected() {
        wallet(1L);
        ChargeResult order = order(1L);

        confirm(1L, order);

        Map<String, Object> charge = charge(order.orderId());
        String encrypted = (String) charge.get("pg_payment_key");
        assertThat(encrypted).startsWith("v1:").doesNotContain(paymentKey(order.orderId()));
        assertThat(paymentKeyCipher.decrypt(encrypted)).isEqualTo(paymentKey(order.orderId()));
        assertThat(charge.get("pg_payment_key_hash")).isEqualTo(paymentKeyCipher.hash(paymentKey(order.orderId())));
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전이 끝난 주문에 같은 결제로 다시 승인을 요청하면 PG를 다시 부르지 않고 처음 결과를 돌려주며 HP·원장은 한 번이다")
    void confirm_replay() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        ChargeResult first = confirm(1L, order);

        ChargeResult again = confirm(1L, order);

        assertThat(again).isEqualTo(first);
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 쓴 결제키를 다른 주문에 쓰거나, 끝난 주문에 다른 결제키를 보내면 CHARGE_PAYMENT_KEY_CONFLICT이고 HP는 바뀌지 않는다")
    void confirm_paymentKeyConflict() {
        Long walletId = wallet(1L);
        ChargeResult first = order(1L);
        ChargeResult second = order(1L);
        confirm(1L, first);

        assertBillingError(() -> chargeConfirmUseCase.confirm(1L, paymentKey(first.orderId()), second.orderId(), AMOUNT),
                BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT);
        assertBillingError(() -> chargeConfirmUseCase.confirm(1L, paymentKey(second.orderId()), first.orderId(), AMOUNT),
                BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT);
        assertThat(charge(second.orderId())).containsEntry("status", "PENDING");
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertWallet(walletId, AMOUNT);
    }

    @Test
    @DisplayName("같은 승인 요청이 동시에 여러 번 와도 PG 승인은 한 번이고 충전 HP·원장도 한 번만 늘어난다")
    void confirm_concurrent() throws Exception {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.confirmDelay(500L);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return confirm(1L, order).status();
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                // 처음 요청은 COMPLETED, 나머지는 같은 결제의 결과 확인(COMPLETED 또는 아직 PROCESSING)이며 오류는 없다
                assertThat(future.get(30, TimeUnit.SECONDS))
                        .isIn(PointChargeStatus.COMPLETED, PointChargeStatus.PROCESSING);
            }
        }

        assertThat(charge(order.orderId())).containsEntry("status", "COMPLETED");
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(TOSS.statusOf(order.orderId())).isEqualTo("DONE");
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 승인 실패
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PG가 승인을 거절하면(카드 거절) FAILED와 PG 코드·메시지를 남기고 HP는 바뀌지 않으며, 같은 주문은 다시 승인할 수 없다")
    void confirm_rejected() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.REJECT_CARD);

        ChargeResult result = confirm(1L, order);

        assertThat(result.status()).isEqualTo(PointChargeStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("REJECT_CARD_PAYMENT: 한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
        assertThat(charge(order.orderId())).containsEntry("status", "FAILED")
                .containsEntry("remaining_principal_hp", 0L);
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
        assertBillingError(() -> confirm(1L, order), BillingErrorCode.CHARGE_ALREADY_FAILED);
        assertThat(TOSS.confirmCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // 결과 불명확(시간 초과·5xx·응답 유실)과 복구
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PG가 승인했지만 응답이 시간 초과되면 FAILED로 끝내지 않고 결제 조회로 승인을 확인해 같은 요청 안에서 충전한다")
    void confirm_timeoutButApproved() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.APPROVE_THEN_TIMEOUT);

        ChargeResult result = confirm(1L, order);

        assertThat(result.status()).isEqualTo(PointChargeStatus.COMPLETED);
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertThat(TOSS.count("GET", "/v1/payments/orders/" + order.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 승인 후 응답이 5xx이고 조회도 실패하면 PROCESSING으로 남고 HP는 그대로이며, 같은 결제의 재요청이 조회로 확인해 한 번만 충전한다")
    void confirm_unknownThenRetried() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.APPROVE_THEN_SERVER_ERROR);
        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);

        ChargeResult unresolved = confirm(1L, order);

        assertThat(unresolved.status()).isEqualTo(PointChargeStatus.PROCESSING);
        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING").containsEntry("failure_reason",
                "CONFIRM_500:UNKNOWN_PAYMENT_ERROR / 500:FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();

        ChargeResult retried = confirm(1L, order);

        assertThat(retried.status()).isEqualTo(PointChargeStatus.COMPLETED);
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        // 재요청은 승인을 다시 하지 않고 조회로 확인했다
        assertThat(TOSS.confirmCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 승인 성공 후 충전 원장 기록이 실패하면 HP·원장은 롤백되고 결제키와 PROCESSING이 남아, 재요청이 조회로 한 번만 충전한다")
    void confirm_dbFailureAfterApproval() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);

        failOnLedgerInsert(() -> assertThatThrownBy(() -> confirm(1L, order)).hasStackTraceContaining("test failure"));

        assertThat(TOSS.statusOf(order.orderId())).isEqualTo("DONE");
        Map<String, Object> charge = charge(order.orderId());
        assertThat(charge).containsEntry("status", "PROCESSING").containsEntry("remaining_principal_hp", 0L);
        assertThat(charge.get("pg_payment_key_hash")).isEqualTo(paymentKeyCipher.hash(paymentKey(order.orderId())));
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();

        ChargeResult retried = confirm(1L, order);

        assertThat(retried.status()).isEqualTo(PointChargeStatus.COMPLETED);
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("회원이 재요청하지 않아도 결과 확인 배치가 오래된 PROCESSING 건을 조회로 확정하고, 방금 바뀐 건은 건드리지 않는다")
    void recovery_batch() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        failOnLedgerInsert(() -> assertThatThrownBy(() -> confirm(1L, order)).hasStackTraceContaining("test failure"));

        // 마지막 변경 1분 이내 건은 대상이 아니다
        assertThat(chargeRecoveryUseCase.recoverStale(Instant.now())).isZero();
        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING");

        assertThat(chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120))).isEqualTo(1);

        assertThat(charge(order.orderId())).containsEntry("status", "COMPLETED");
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        // 확정된 건은 다음 실행의 대상이 아니다
        assertThat(chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120))).isZero();
    }

    @Test
    @DisplayName("승인 요청이 PG에 닿지 않아 조회가 404(승인 전)이면 FAILED로 끝내지 않고, 결과 확인이 같은 멱등키로 승인을 다시 요청해 충전한다")
    void recovery_reconfirmWhenNotReached() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.UNREACHABLE);

        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.PROCESSING);
        assertThat(charge(order.orderId())).containsEntry("failure_reason", "CONFIRM_NO_RESPONSE / LOOKUP_404:NOT_FOUND_PAYMENT");
        assertWallet(walletId, 0L);

        assertThat(chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120))).isEqualTo(1);

        assertThat(charge(order.orderId())).containsEntry("status", "COMPLETED");
        assertWallet(walletId, AMOUNT);
        assertThat(TOSS.confirmCount()).isEqualTo(2);
        assertThat(TOSS.requests()).filteredOn(request -> request.method().equals("POST"))
                .extracting(FakeTossPaymentsServer.Request::idempotencyKey).containsOnly(
                        "hapbang-charge-confirm-" + order.orderId());
    }

    @Test
    @DisplayName("결과를 모르는 동안 PG에서 결제가 만료·취소되면 결과 확인이 FAILED로 끝내고 HP는 바꾸지 않는다")
    void recovery_expiredAtPg() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.TIMEOUT_NOT_APPROVED);
        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);
        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.PROCESSING);
        TOSS.setStatus(order.orderId(), "EXPIRED");

        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));

        assertThat(charge(order.orderId())).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "PG_STATUS_EXPIRED");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    @DisplayName("PG 승인 금액이 주문 금액과 다르면 충전하지 않고 CHARGE_STATE_INCONSISTENT로 알리며 PROCESSING·사유를 운영자 확인용으로 남긴다")
    void confirm_approvedAmountMismatch() {
        Long walletId = wallet(1L);
        ChargeResult order = chargeOrderUseCase.create(1L, AMOUNT);
        // PG에는 다른 금액으로 승인된 결제가 있다(조회 결과)
        TOSS.authorize(order.orderId(), paymentKey(order.orderId()), 20_000L);
        TOSS.setStatus(order.orderId(), "DONE");
        TOSS.nextConfirm(order.orderId(), Fault.SERVER_ERROR);

        assertBillingError(() -> confirm(1L, order), BillingErrorCode.CHARGE_STATE_INCONSISTENT);

        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING")
                .containsEntry("failure_reason", "APPROVED_AMOUNT_MISMATCH:20000");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 기존 보류 엔진과의 연결 (회귀)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("충전한 HP는 기존 보류(FIFO 원금 배분)·반환(원금 복원)에 그대로 쓰이고 원금 합계 = 충전 HP 불변식이 유지된다")
    void chargedHpUsedByHoldAndRelease() {
        Long walletId = wallet(1L);
        confirm(1L, order(1L));

        holdFacade.hold(new HoldCommand(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 501L, 3_000L));

        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getHeldChargeHp).containsExactly(7_000L, 3_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT remaining_principal_hp FROM billing.point_charge WHERE wallet_id = ?",
                Long.class, walletId)).isEqualTo(7_000L);

        holdFacade.release(ReleaseCommand.full(1L, BillingReferenceType.LECTURE_ORDER_ITEM, 501L, "CANCEL-501"));

        assertWallet(walletId, AMOUNT);
        assertThat(jdbcTemplate.queryForObject("SELECT remaining_principal_hp FROM billing.point_charge WHERE wallet_id = ?",
                Long.class, walletId)).isEqualTo(AMOUNT);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 2차 검토: 경합·결제 식별자·복구 공백
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("다른 주문이 같은 결제키로 먼저 커밋하면(사전 확인 통과 뒤) HMAC UNIQUE 위반을 409 CHARGE_PAYMENT_KEY_CONFLICT로 바꾸고 PG를 부르지 않는다")
    void confirm_paymentKeyHashUniqueRace() throws Exception {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        String hash = paymentKeyCipher.hash(paymentKey(order.orderId()));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // 다른 주문이 같은 결제키로 승인 요청을 시작했지만 아직 커밋하지 않았다(사전 존재 확인에 보이지 않는다)
            Future<?> other = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("""
                        INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                            pg_provider, pg_order_id, pg_payment_key, pg_payment_key_hash, status, requested_at,
                            created_at, updated_at)
                        VALUES (?, 10000, 10000, 0, 'TOSS_PAYMENTS', 'HPCHG-other-order', 'v1:other', ?, 'PROCESSING',
                            now(), now(), now())""", walletId, hash);
                inserted.countDown();
                awaitLatch(commit);
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> confirming = executor.submit(() -> {
                try {
                    return confirm(1L, order).status();
                } catch (BillingException exception) {
                    return exception.getErrorCode();
                }
            });
            // 승인 요청 시작이 HMAC 유일 인덱스에서 상대 커밋을 기다린다
            awaitLockWaiters(1);
            commit.countDown();
            other.get(10, TimeUnit.SECONDS);
            assertThat(confirming.get(10, TimeUnit.SECONDS)).isEqualTo(BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT);
        }

        assertThat(charge(order.orderId())).containsEntry("status", "PENDING").containsEntry("pg_payment_key_hash", null);
        assertThat(TOSS.confirmCount()).isZero();
        assertWallet(walletId, 0L);
    }

    @Test
    @DisplayName("승인 후 DB 반영에 실패한 주문을 같은 결제 재요청 1개와 결과 확인 배치 2개가 동시에 처리해도 충전 원장·HP는 한 번이다")
    void recovery_concurrentRequestAndBatches() throws Exception {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        failOnLedgerInsert(() -> assertThatThrownBy(() -> confirm(1L, order)).hasStackTraceContaining("test failure"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> request = executor.submit(() -> {
                awaitLatch(start);
                return confirm(1L, order).status();
            });
            Future<?> batch1 = executor.submit(() -> {
                awaitLatch(start);
                return chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));
            });
            Future<?> batch2 = executor.submit(() -> {
                awaitLatch(start);
                return chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));
            });
            start.countDown();
            assertThat(request.get(30, TimeUnit.SECONDS)).isIn(PointChargeStatus.COMPLETED, PointChargeStatus.PROCESSING);
            batch1.get(30, TimeUnit.SECONDS);
            batch2.get(30, TimeUnit.SECONDS);
        }

        assertThat(charge(order.orderId())).containsEntry("status", "COMPLETED");
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
        // PG에서 이미 DONE이라 조회로만 확정했고 승인을 다시 요청하지 않았다
        assertThat(TOSS.confirmCount()).isEqualTo(1);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("실패로 끝난 주문은 PG에 승인된 결제가 나타나도 COMPLETED가 되지 않는다: 재요청은 CHARGE_ALREADY_FAILED, 배치 대상 아님, 확정 단계도 거절")
    void failedNeverCompleted() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.REJECT_CARD);
        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.FAILED);
        TOSS.setStatus(order.orderId(), "DONE");

        assertBillingError(() -> confirm(1L, order), BillingErrorCode.CHARGE_ALREADY_FAILED);
        assertThat(chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120))).isZero();
        Long chargeId = (Long) charge(order.orderId()).get("id");
        assertThat(chargeProcessing.complete(chargeId, new TossPayment(paymentKey(order.orderId()), order.orderId(),
                "DONE", AMOUNT, null, "카드"))).isInstanceOf(ChargeProcessing.Completion.Mismatch.class);

        assertThat(charge(order.orderId())).containsEntry("status", "FAILED");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    @DisplayName("이 주문으로 승인된 결제라도 결제키가 요청한 결제와 다르면 충전하지 않고 CHARGE_STATE_INCONSISTENT·사유를 남긴다")
    void confirm_approvedPaymentKeyMismatch() {
        Long walletId = wallet(1L);
        ChargeResult order = chargeOrderUseCase.create(1L, AMOUNT);
        TOSS.authorize(order.orderId(), "tgen_another_payment", AMOUNT);
        TOSS.setStatus(order.orderId(), "DONE");
        TOSS.nextConfirm(order.orderId(), Fault.SERVER_ERROR);

        assertBillingError(() -> confirm(1L, order), BillingErrorCode.CHARGE_STATE_INCONSISTENT);

        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING");
        assertThat((String) charge(order.orderId()).get("failure_reason")).startsWith("APPROVED_PAYMENT_KEY_MISMATCH");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    @DisplayName("PG가 일부만 취소한(PARTIAL_CANCELED) 결제는 돈 일부가 PG에 남아 FAILED로 끝내지 않고 PROCESSING·사유로 운영자 확인에 남긴다")
    void recovery_partialCanceledKeptForOperator() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.APPROVE_THEN_SERVER_ERROR);
        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);
        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.PROCESSING);
        TOSS.setStatus(order.orderId(), "PARTIAL_CANCELED");

        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));

        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING")
                .containsEntry("failure_reason", "PG_STATUS_PARTIAL_CANCELED");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    @DisplayName("같은 사유로 미확정이 반복돼도 확인할 때마다 updated_at이 갱신되어, 배치(오래된 순 100건)가 같은 미확정 건만 반복해 다른 건을 굶기지 않는다")
    void recovery_unresolvedRotates() {
        wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.SERVER_ERROR);
        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);
        confirm(1L, order);
        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);
        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));
        Instant second = updatedAt(order);

        TOSS.nextLookup(order.orderId(), Fault.SERVER_ERROR);
        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(240));

        // 사유는 두 번 모두 같지만(조회 5xx) 확인 시각은 갱신된다
        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING")
                .containsEntry("failure_reason", "500:FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING");
        assertThat(updatedAt(order)).isAfter(second);
    }

    @Test
    @DisplayName("결제키 암호문을 복호화할 수 없어도 PG 조회가 DONE이면 결제키 HMAC으로 대조해 충전하고, 재승인이 필요하면 PROCESSING·사유로 남긴다")
    void recovery_unreadablePaymentKey() {
        Long walletId = wallet(1L);
        ChargeResult approved = order(1L);
        failOnLedgerInsert(() -> assertThatThrownBy(() -> confirm(1L, approved)).hasStackTraceContaining("test failure"));
        ChargeResult notReached = order(1L);
        TOSS.nextConfirm(notReached.orderId(), Fault.UNREACHABLE);
        assertThat(confirm(1L, notReached).status()).isEqualTo(PointChargeStatus.PROCESSING);
        // 암호화 키가 바뀌어 기존 암호문을 읽을 수 없게 된 상황
        jdbcTemplate.update("UPDATE billing.point_charge SET pg_payment_key = 'v1:corrupted-' || id");

        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));

        assertThat(charge(approved.orderId())).containsEntry("status", "COMPLETED");
        assertThat(charge(notReached.orderId())).containsEntry("status", "PROCESSING")
                .containsEntry("failure_reason", "PAYMENT_KEY_UNREADABLE / LOOKUP_404:NOT_FOUND_PAYMENT");
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 404(승인 전) 상태로 결제 세션이 만료되면, 결과 확인의 같은 멱등키 재승인이 NOT_FOUND_PAYMENT_SESSION으로 거절되어 FAILED·HP 변화 없음")
    void recovery_notFoundThenSessionExpired() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.UNREACHABLE);
        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.PROCESSING);
        TOSS.expireSession(order.orderId());

        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));

        assertThat(charge(order.orderId())).containsEntry("status", "FAILED");
        assertThat((String) charge(order.orderId()).get("failure_reason")).startsWith("NOT_FOUND_PAYMENT_SESSION:");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
        assertThat(TOSS.confirmCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("PG가 409 IDEMPOTENT_REQUEST_PROCESSING으로 답하면 FAILED로 끝내지 않고 PROCESSING을 유지하며, 결과 확인이 같은 멱등키로 다시 승인해 한 번만 충전한다")
    void confirm_conflictProcessingKeptThenRecovered() {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), Fault.CONFLICT_PROCESSING);

        ChargeResult result = confirm(1L, order);

        assertThat(result.status()).isEqualTo(PointChargeStatus.PROCESSING);
        assertThat(charge(order.orderId())).containsEntry("failure_reason",
                "CONFIRM_409:IDEMPOTENT_REQUEST_PROCESSING / LOOKUP_404:NOT_FOUND_PAYMENT");
        assertWallet(walletId, 0L);

        chargeRecoveryUseCase.recoverStale(Instant.now().plusSeconds(120));

        assertThat(charge(order.orderId())).containsEntry("status", "COMPLETED");
        assertWallet(walletId, AMOUNT);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"RATE_LIMITED", "AUTH_ERROR"})
    @DisplayName("요청 한도(429)·시크릿 키 오류(401)는 PG가 결제를 판단하지 않은 것이라 FAILED로 끝내지 않고 PROCESSING을 유지한다")
    void confirm_rateLimitOrAuthErrorKept(Fault fault) {
        Long walletId = wallet(1L);
        ChargeResult order = order(1L);
        TOSS.nextConfirm(order.orderId(), fault);

        assertThat(confirm(1L, order).status()).isEqualTo(PointChargeStatus.PROCESSING);

        assertThat(charge(order.orderId())).containsEntry("status", "PROCESSING");
        assertWallet(walletId, 0L);
        assertThat(ledgerCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private Instant updatedAt(ChargeResult order) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM billing.point_charge WHERE pg_order_id = ?",
                Timestamp.class, order.orderId()).toInstant();
    }

    // 다른 트랜잭션의 락을 기다리는 세션이 n개 이상이 될 때까지 기다린다
    private void awaitLockWaiters(int sessions) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting >= sessions) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("락 대기 세션이 생기지 않았습니다.");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 주문을 만들고, 결제창 인증을 마친 결제를 가짜 PG에 등록한다.
    private ChargeResult order(Long userId) {
        ChargeResult order = chargeOrderUseCase.create(userId, AMOUNT);
        TOSS.authorize(order.orderId(), paymentKey(order.orderId()), AMOUNT);
        return order;
    }

    private ChargeResult confirm(Long userId, ChargeResult order) {
        return chargeConfirmUseCase.confirm(userId, paymentKey(order.orderId()), order.orderId(), order.amount());
    }

    private Map<String, Object> charge(String orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.point_charge WHERE pg_order_id = ?", orderId);
    }

    private int chargeCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge", Integer.class);
    }

    private int ledgerCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'CHARGE'", Integer.class);
    }

    private List<String> ledgerLines(String operationKey) {
        return jdbcTemplate.queryForList("""
                SELECT wallet_id || ' ' || point_type || ' ' || balance_bucket || ' ' || amount_hp || ' '
                    || transaction_type || ' ' || reference_type || ' ' || reference_id
                FROM billing.point_ledger WHERE operation_key = ? ORDER BY id""", String.class, operationKey);
    }

    private void assertWallet(Long walletId, long chargeBalanceHp) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getRevenueBalanceHp, Wallet::getHeldChargeHp,
                        Wallet::getHeldRevenueHp)
                .containsExactly(chargeBalanceHp, 0L, 0L, 0L);
    }

    // 충전 원장 INSERT에 실패 트리거를 잠시 걸고 동작을 실행한 뒤 지운다(PG 승인 성공 후 DB 반영 실패 재현).
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
