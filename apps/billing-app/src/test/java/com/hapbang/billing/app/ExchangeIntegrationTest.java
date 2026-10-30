package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PaymentKeyProperties;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.testsupport.IntegrationTest;

// 수익 HP 환전 신청·보류·취소(#285)를 실제 PostgreSQL로 검증한다. 실제 은행 API·송금은 없다.
// 수익 HP는 강의 결제 보류 → 판매자 지급(BillingPort)으로 만들어 실제 흐름과 같게 한다.
// 모든 시나리오 끝에 정합성(지갑 = 원장, 신청 금액 = 보류 원금(수익 HP만), REQUESTED는 전액 보류 중·CANCELED는 전액 반환,
// 환전 원장은 수익 HP만, 수수료 + 예상 수령액 = 신청 금액)을 확인한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273)
@WithMockUser(roles = "INTERNAL")
class ExchangeIntegrationTest {

    private static final long BUYER = 1L;
    private static final long INFLUENCER = 2L;
    private static final long OTHER = 3L;
    private static final String BANK = "004";
    private static final String ACCOUNT = "12345678901234";
    private static final String HOLDER = "홍길동";
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    ExchangeProcessing exchangeProcessing;

    @Autowired
    BillingPort billingPort;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 신청·보류·계좌 스냅샷
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("환전 신청은 수익 HP만 REVENUE_ONLY로 보류하고 수수료(10%)·예상 수령액·정산 회차·테스트 계좌 스냅샷을 저장한다")
    void request() {
        Long walletId = influencerWithRevenue(20_000L);
        charge(walletId, 10_000L);
        Instant before = Instant.now();

        ExchangeResult result = request(INFLUENCER, "key-1", 15_000L);

        assertThat(result.status()).isEqualTo(ExchangeStatus.REQUESTED);
        assertThat(result.requestedAmountHp()).isEqualTo(15_000L);
        assertThat(result.feeAmountHp()).isEqualTo(1_500L);
        assertThat(result.payoutAmountKrw()).isEqualTo(13_500L);
        assertThat(result.settlementRoundDate())
                .isEqualTo(ExchangeSettlementRound.dateOf(ExchangeSettlementRound.of(before)));
        assertThat(result.account()).isEqualTo(new ExchangeResult.Account(BANK, "**********1234", HOLDER,
                AccountVerificationStatus.PENDING));
        // 수익 HP만 보류하고 충전 HP는 그대로다
        assertWallet(walletId, 10_000L, 0L, 5_000L, 15_000L);
        Map<String, Object> hold = holdOf(result.exchangeId());
        assertThat(hold).containsEntry("hold_type", "REVENUE_ONLY").containsEntry("original_revenue_hp", 15_000L)
                .containsEntry("original_charge_hp", 0L).containsEntry("held_revenue_hp", 15_000L)
                .containsEntry("status", "HELD");
        assertThat(exchangeRow(result.exchangeId())).containsEntry("point_hold_id", hold.get("id"))
                .containsEntry("idempotency_key", "EXCHANGE:" + walletId + ":key-1");
        assertThat(ledgerOf(result.exchangeId())).containsExactly("REVENUE AVAILABLE -15000 HOLD",
                "REVENUE HELD 15000 HOLD");
        // 충전 HP 원금(환불 FIFO 기준)과 충전 건 배분은 건드리지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(remaining_principal_hp) FROM billing.point_charge WHERE wallet_id = ?", Long.class, walletId))
                .isEqualTo(10_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge_usage u "
                + "JOIN billing.point_hold h ON h.id = u.point_hold_id WHERE h.reference_type = 'EXCHANGE'", Integer.class))
                .isZero();
        assertConsistent();
    }

    @Test
    @DisplayName("계좌 스냅샷 저장이 실패하면 같은 트랜잭션의 신청·수익 HP 보류·원장도 모두 롤백된다")
    void accountSaveFailureRollsBackAll() {
        Long walletId = influencerWithRevenue(10_000L);

        // bank_code는 VARCHAR(255)라 DB가 거절한다(API는 숫자 3자리만 받으므로 UseCase에 직접 넣는다)
        assertThatThrownBy(() -> exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(3_000L, "1".repeat(300), ACCOUNT, HOLDER)))
                .isInstanceOf(RuntimeException.class)
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain(ACCOUNT));

        assertNothingCreated();
        assertWallet(walletId, 0L, 0L, 10_000L, 0L);
    }

    @Test
    @DisplayName("계좌번호는 암호문으로만 저장하고, 실제 확인을 하지 않았으므로 검증 상태는 PENDING이다. 예금주는 앞뒤 공백을 뺀다")
    void accountSnapshotEncrypted() {
        influencerWithRevenue(5_000L);

        Long exchangeId = exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(1_000L, BANK, ACCOUNT, "  " + HOLDER + " "));
        ExchangeResult result = exchangeProcessing.get(INFLUENCER, exchangeId);

        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT * FROM billing.exchange_account WHERE exchange_id = ?", result.exchangeId());
        String encrypted = (String) account.get("account_number_encrypted");
        assertThat(encrypted).startsWith("v1:").doesNotContain(ACCOUNT).doesNotContain("5678901234");
        assertThat(account).containsEntry("bank_code", BANK).containsEntry("account_holder_name", HOLDER)
                .containsEntry("verification_status", "PENDING").containsEntry("verified_at", null);
        assertThat(result.account().accountNumberMasked()).isEqualTo("**********1234");
        assertThat(result.toString()).doesNotContain(ACCOUNT);
    }

    @Test
    @DisplayName("충전 HP는 환전할 수 없다: 수익 HP가 없으면 INSUFFICIENT_BALANCE이고 신청·보류·계좌를 남기지 않는다")
    void chargeHpNotExchangeable() {
        Long walletId = wallet(INFLUENCER);
        charge(walletId, 10_000L);

        assertBillingError(() -> request(INFLUENCER, "key-1", 1_000L), BillingErrorCode.INSUFFICIENT_BALANCE);

        assertNothingCreated();
        assertWallet(walletId, 10_000L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("가용 수익 HP보다 많이 신청하면 INSUFFICIENT_BALANCE이고 아무것도 남기지 않는다")
    void insufficientRevenue() {
        Long walletId = influencerWithRevenue(5_000L);

        assertBillingError(() -> request(INFLUENCER, "key-1", 6_000L), BillingErrorCode.INSUFFICIENT_BALANCE);

        assertNothingCreated();
        assertWallet(walletId, 0L, 0L, 5_000L, 0L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -1_000L})
    @DisplayName("1 HP보다 작으면 EXCHANGE_AMOUNT_TOO_SMALL이고 아무것도 남기지 않는다 (#453)")
    void invalidAmount(long amountHp) {
        Long walletId = influencerWithRevenue(20_000L);

        assertBillingError(() -> request(INFLUENCER, "key-1", amountHp), BillingErrorCode.EXCHANGE_AMOUNT_TOO_SMALL);

        assertNothingCreated();
        assertWallet(walletId, 0L, 0L, 20_000L, 0L);
    }

    @Test
    @DisplayName("지갑이 없으면 WALLET_NOT_FOUND")
    void walletNotFound() {
        assertBillingError(() -> request(INFLUENCER, "key-1", 1_000L), BillingErrorCode.WALLET_NOT_FOUND);
        assertNothingCreated();
    }

    @Test
    @DisplayName("다른 키의 신청은 여러 건 할 수 있고, 가용 수익 HP 안에서만 보류한다")
    void multipleRequests() {
        Long walletId = influencerWithRevenue(10_000L);

        ExchangeResult first = request(INFLUENCER, "key-1", 5_000L);
        ExchangeResult second = request(INFLUENCER, "key-2", 5_000L);
        assertBillingError(() -> request(INFLUENCER, "key-3", 1_000L), BillingErrorCode.INSUFFICIENT_BALANCE);

        assertThat(first.exchangeId()).isNotEqualTo(second.exchangeId());
        assertThat(exchangeCount()).isEqualTo(2);
        assertWallet(walletId, 0L, 0L, 0L, 10_000L);
        assertConsistent();
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 Idempotency-Key·같은 내용의 재요청은 처음 신청이고 다시 보류하지 않는다. 금액·계좌가 다르면 IDEMPOTENCY_KEY_CONFLICT")
    void idempotentReplay() {
        Long walletId = influencerWithRevenue(10_000L);
        ExchangeResult first = request(INFLUENCER, "key-1", 3_000L);

        String storedCiphertext = jdbcTemplate.queryForObject(
                "SELECT account_number_encrypted FROM billing.exchange_account", String.class);

        // 같은 계좌번호를 다시 보내면 새 암호문은 다르지만(무작위 IV) 원문 비교로 같은 신청이다. 다시 보류하지 않는다
        ExchangeResult replay = request(INFLUENCER, "key-1", 3_000L);
        ExchangeResult replayAgain = request(INFLUENCER, "key-1", 3_000L);
        assertThat(replay).isEqualTo(first);
        assertThat(replayAgain).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_hold WHERE reference_type = 'EXCHANGE'", Integer.class)).isEqualTo(1);
        assertThat(ledgerOf(first.exchangeId())).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT account_number_encrypted FROM billing.exchange_account",
                String.class)).isEqualTo(storedCiphertext);

        assertBillingError(() -> request(INFLUENCER, "key-1", 4_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(3_000L, "088", ACCOUNT, HOLDER)), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(3_000L, BANK, "12345678901235", HOLDER)), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(3_000L, BANK, ACCOUNT, "김철수")), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(exchangeCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.exchange_account", Integer.class))
                .isEqualTo(1);
        assertWallet(walletId, 0L, 0L, 7_000L, 3_000L);
        // 다른 회원의 같은 키는 다른 신청이다
        Long otherWallet = revenueWallet(OTHER, 2_000L);
        assertThat(request(OTHER, "key-1", 1_000L).exchangeId()).isNotEqualTo(first.exchangeId());
        assertWallet(otherWallet, 0L, 0L, 1_000L, 1_000L);
        assertConsistent();
    }

    @Test
    @DisplayName("저장된 계좌번호를 복호화할 수 없으면 같은 키 재요청은 EXCHANGE_ACCOUNT_UNREADABLE이고 새 신청·보류·충돌로 처리하지 않는다")
    void replayWithUnreadableAccount() {
        Long walletId = influencerWithRevenue(10_000L);
        ExchangeResult first = request(INFLUENCER, "key-1", 3_000L);
        // 키 변경·암호문 손상을 같은 결과(GCM 인증 실패)로 재현한다
        jdbcTemplate.update("UPDATE billing.exchange_account SET account_number_encrypted = ? WHERE exchange_id = ?",
                "v1:" + Base64.getEncoder().encodeToString(new byte[40]), first.exchangeId());

        assertBillingError(() -> request(INFLUENCER, "key-1", 3_000L), BillingErrorCode.EXCHANGE_ACCOUNT_UNREADABLE);
        assertBillingError(() -> exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(3_000L, BANK, "99999999", HOLDER)), BillingErrorCode.EXCHANGE_ACCOUNT_UNREADABLE);

        assertThat(exchangeCount()).isEqualTo(1);
        assertThat(ledgerOf(first.exchangeId())).hasSize(2);
        assertWallet(walletId, 0L, 0L, 7_000L, 3_000L);
        // 조회는 막지 않고 계좌번호를 전부 가린다. 취소(보류 반환)도 계좌와 무관하게 된다
        assertThat(exchangeProcessing.get(INFLUENCER, first.exchangeId()).account().accountNumberMasked())
                .isEqualTo(ExchangeAccountCipher.UNREADABLE_MASK);
        assertThat(exchangeProcessing.cancel(INFLUENCER, first.exchangeId()).status())
                .isEqualTo(ExchangeStatus.CANCELED);
        assertWallet(walletId, 0L, 0L, 10_000L, 0L);
    }

    @Test
    @DisplayName("저장된 암호문은 애플리케이션 재시작 뒤 같은 키의 새 암호화 인스턴스로 복호화해 비교·마스킹할 수 있다")
    void readableWithSameKeyAfterRestart() {
        influencerWithRevenue(10_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 3_000L).exchangeId();
        String stored = jdbcTemplate.queryForObject(
                "SELECT account_number_encrypted FROM billing.exchange_account WHERE exchange_id = ?", String.class,
                exchangeId);

        ExchangeAccountCipher restarted = new ExchangeAccountCipher(new PaymentKeyCipher(
                new PaymentKeyProperties(ChargeFixture.ENCRYPTION_KEY, ChargeFixture.HMAC_KEY)));

        assertThat(restarted.matches(exchangeId, stored, ACCOUNT)).isTrue();
        assertThat(restarted.masked(exchangeId, stored)).isEqualTo("**********1234");
    }

    @Test
    @DisplayName("취소한 신청을 같은 키로 다시 요청하면 새로 보류하지 않고 취소된 처음 신청을 돌려준다")
    void replayAfterCancel() {
        Long walletId = influencerWithRevenue(5_000L);
        ExchangeResult first = request(INFLUENCER, "key-1", 5_000L);
        exchangeProcessing.cancel(INFLUENCER, first.exchangeId());

        ExchangeResult replay = request(INFLUENCER, "key-1", 5_000L);

        assertThat(replay.exchangeId()).isEqualTo(first.exchangeId());
        assertThat(replay.status()).isEqualTo(ExchangeStatus.CANCELED);
        assertWallet(walletId, 0L, 0L, 5_000L, 0L);
        assertConsistent();
    }

    // ---------------------------------------------------------------------------------------------
    // 동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("동시 신청(다른 키)은 지갑 락으로 직렬화되어 가용 수익 HP를 넘겨 보류하지 않는다")
    void concurrentRequestsDoNotOverHold() throws Exception {
        Long walletId = influencerWithRevenue(10_000L);

        List<Object> outcomes = runConcurrently(6, index -> request(INFLUENCER, "key-" + index, 3_000L));

        assertThat(outcomes).filteredOn(ExchangeResult.class::isInstance).hasSize(3);
        assertThat(outcomes).filteredOn(outcome -> outcome == BillingErrorCode.INSUFFICIENT_BALANCE).hasSize(3);
        assertThat(exchangeCount()).isEqualTo(3);
        assertWallet(walletId, 0L, 0L, 1_000L, 9_000L);
        assertConsistent();
    }

    @Test
    @DisplayName("같은 키 동시 신청은 한 건만 만들고 모두 같은 신청을 돌려준다")
    void concurrentSameKey() throws Exception {
        Long walletId = influencerWithRevenue(10_000L);

        List<Object> outcomes = runConcurrently(5, index -> request(INFLUENCER, "key-1", 2_000L));

        assertThat(outcomes).allMatch(ExchangeResult.class::isInstance);
        assertThat(outcomes).extracting(outcome -> ((ExchangeResult) outcome).exchangeId()).containsOnly(
                ((ExchangeResult) outcomes.getFirst()).exchangeId());
        assertThat(exchangeCount()).isEqualTo(1);
        assertWallet(walletId, 0L, 0L, 8_000L, 2_000L);
        assertConsistent();
    }

    // ---------------------------------------------------------------------------------------------
    // 조회·취소
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("REQUESTED 신청을 취소하면 CANCELED이고 보류한 수익 HP를 전부 반환한다. 반복 취소는 다시 반환하지 않는다")
    void cancel() {
        Long walletId = influencerWithRevenue(10_000L);
        ExchangeResult requested = request(INFLUENCER, "key-1", 4_000L);

        ExchangeResult canceled = exchangeProcessing.cancel(INFLUENCER, requested.exchangeId());

        assertThat(canceled.status()).isEqualTo(ExchangeStatus.CANCELED);
        assertThat(canceled.canceledAt()).isNotNull();
        assertWallet(walletId, 0L, 0L, 10_000L, 0L);
        assertThat(holdOf(requested.exchangeId())).containsEntry("held_revenue_hp", 0L)
                .containsEntry("status", "RELEASED");
        assertThat(ledgerOf(requested.exchangeId())).containsExactly("REVENUE AVAILABLE -4000 HOLD",
                "REVENUE HELD 4000 HOLD", "REVENUE HELD -4000 RELEASE", "REVENUE AVAILABLE 4000 RELEASE");

        ExchangeResult again = exchangeProcessing.cancel(INFLUENCER, requested.exchangeId());
        assertThat(again).isEqualTo(canceled);
        assertThat(ledgerOf(requested.exchangeId())).hasSize(4);
        assertThat(releaseDetailCount(requested.exchangeId())).isEqualTo(1);
        assertWallet(walletId, 0L, 0L, 10_000L, 0L);
        assertConsistent();
    }

    @Test
    @DisplayName("동시 취소는 한 번만 반환하고 둘 다 CANCELED를 돌려준다")
    void concurrentCancel() throws Exception {
        Long walletId = influencerWithRevenue(10_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 4_000L).exchangeId();

        List<Object> outcomes = runConcurrently(4, index -> exchangeProcessing.cancel(INFLUENCER, exchangeId));

        assertThat(outcomes).allSatisfy(outcome ->
                assertThat(((ExchangeResult) outcome).status()).isEqualTo(ExchangeStatus.CANCELED));
        assertThat(releaseDetailCount(exchangeId)).isEqualTo(1);
        assertWallet(walletId, 0L, 0L, 10_000L, 0L);
        assertConsistent();
    }

    @Test
    @DisplayName("잠금 계약: 다른 트랜잭션이 지갑 → 환전 순서로 잠그고 상태를 바꾸는 동안 취소는 기다렸다가 최신 상태를 다시 읽어 거절한다(중복 반환 없음)")
    void cancelWaitsForConcurrentStatusChange() throws Exception {
        Long walletId = influencerWithRevenue(10_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 4_000L).exchangeId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 후속 관리자 승인과 같은 잠금 순서(지갑 → 환전)로 상태를 바꾸는 트랜잭션. 승인 기능은 없으므로 송금 단계 상태로 대신한다
            Future<?> approval = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        jdbcTemplate.queryForObject("SELECT id FROM billing.wallet WHERE id = ? FOR UPDATE", Long.class,
                                walletId);
                        jdbcTemplate.queryForObject("SELECT id FROM billing.exchange WHERE id = ? FOR UPDATE", Long.class,
                                exchangeId);
                        jdbcTemplate.update("UPDATE billing.exchange SET status = 'PROCESSING' WHERE id = ?", exchangeId);
                        locked.countDown();
                        awaitQuietly(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> cancel = executor.submit(() -> {
                try {
                    return exchangeProcessing.cancel(INFLUENCER, exchangeId);
                } catch (BillingException exception) {
                    return exception.getErrorCode();
                }
            });
            // 취소는 지갑 락에서 기다린다
            Thread.sleep(500);
            assertThat(cancel.isDone()).isFalse();
            release.countDown();
            approval.get(10, TimeUnit.SECONDS);

            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo(BillingErrorCode.EXCHANGE_NOT_CANCELABLE);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "PROCESSING").containsEntry("canceled_at", null);
        assertThat(releaseDetailCount(exchangeId)).isZero();
        assertWallet(walletId, 0L, 0L, 6_000L, 4_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "COMPLETED", "FAILED", "UNKNOWN"})
    @DisplayName("관리자 승인 뒤(송금 단계) 신청은 EXCHANGE_NOT_CANCELABLE이고 보류를 유지한다")
    void notCancelableAfterApproval(String status) {
        Long walletId = influencerWithRevenue(10_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 4_000L).exchangeId();
        // 그 상태로 넘어간 신청을 직접 만든다. 완료·실패는 시각이 함께 있어야 한다(ck_exchange_10·11, #304)
        jdbcTemplate.update("""
                UPDATE billing.exchange SET status = ?,
                    completed_at = CASE WHEN ? = 'COMPLETED' THEN now() END,
                    failed_at = CASE WHEN ? = 'FAILED' THEN now() END
                WHERE id = ?""", status, status, status, exchangeId);

        assertBillingError(() -> exchangeProcessing.cancel(INFLUENCER, exchangeId),
                BillingErrorCode.EXCHANGE_NOT_CANCELABLE);

        assertThat(exchangeRow(exchangeId)).containsEntry("status", status).containsEntry("canceled_at", null);
        assertThat(releaseDetailCount(exchangeId)).isZero();
        assertWallet(walletId, 0L, 0L, 6_000L, 4_000L);
    }

    @Test
    @DisplayName("다른 회원의 신청은 조회·취소할 수 없고(EXCHANGE_NOT_FOUND) 목록에도 나오지 않는다")
    void otherMembersExchange() {
        influencerWithRevenue(10_000L);
        Long otherWallet = revenueWallet(OTHER, 1_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 4_000L).exchangeId();

        assertBillingError(() -> exchangeProcessing.get(OTHER, exchangeId), BillingErrorCode.EXCHANGE_NOT_FOUND);
        assertBillingError(() -> exchangeProcessing.cancel(OTHER, exchangeId), BillingErrorCode.EXCHANGE_NOT_FOUND);
        assertBillingError(() -> exchangeProcessing.get(INFLUENCER, exchangeId + 100),
                BillingErrorCode.EXCHANGE_NOT_FOUND);
        assertThat(exchangeProcessing.list(OTHER, PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(exchangeRow(exchangeId)).containsEntry("status", "REQUESTED");
        assertWallet(otherWallet, 0L, 0L, 1_000L, 0L);
    }

    @Test
    @DisplayName("내 신청 목록은 최신순 페이지이고 계좌번호는 마스킹한다")
    void list() {
        influencerWithRevenue(10_000L);
        Long first = request(INFLUENCER, "key-1", 1_000L).exchangeId();
        Long second = request(INFLUENCER, "key-2", 2_000L).exchangeId();
        Long third = exchangeRequestUseCase.create(INFLUENCER, "key-3",
                new ExchangeCommand(3_000L, "088", "98765432", HOLDER));

        Page<ExchangeResult> page = exchangeProcessing.list(INFLUENCER, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ExchangeResult::exchangeId).containsExactly(third, second);
        assertThat(page.getContent().getFirst().account().accountNumberMasked()).isEqualTo("****5432");
        assertThat(exchangeProcessing.list(INFLUENCER, PageRequest.of(1, 2)).getContent())
                .extracting(ExchangeResult::exchangeId).containsExactly(first);
    }

    // ---------------------------------------------------------------------------------------------
    // 강의·출연 금융 기능 회귀
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("환전 보류 중에도 강의 결제 보류·반환·지급은 그대로 동작하고, 환전 보류 금액은 다른 보류에 쓰이지 않는다")
    void otherHoldsUnaffected() {
        Long walletId = influencerWithRevenue(10_000L);
        charge(walletId, 5_000L);
        Long exchangeId = request(INFLUENCER, "key-1", 8_000L).exchangeId();

        // 인플루언서가 강의를 사면 남은 수익 HP 2,000을 먼저 쓰고 충전 HP 3,000을 쓴다(환전 보류분은 쓰지 않는다)
        billingPort.hold(new HoldCommand(INFLUENCER, BillingReferenceType.LECTURE_ORDER_ITEM, 9_001L, 5_000L));
        assertWallet(walletId, 2_000L, 3_000L, 0L, 10_000L);
        billingPort.release(ReleaseCommand.full(INFLUENCER, BillingReferenceType.LECTURE_ORDER_ITEM, 9_001L,
                "CANCEL-9001"));
        assertWallet(walletId, 5_000L, 0L, 2_000L, 8_000L);

        exchangeProcessing.cancel(INFLUENCER, exchangeId);
        assertWallet(walletId, 5_000L, 0L, 10_000L, 0L);
        assertConsistent();
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private ExchangeResult request(Long userId, String key, long amountHp) {
        Long exchangeId = exchangeRequestUseCase.create(userId, key, new ExchangeCommand(amountHp, BANK, ACCOUNT,
                HOLDER));
        return exchangeProcessing.get(userId, exchangeId);
    }

    // 구매자가 강의를 결제(보류)하고 인플루언서에게 지급해 인플루언서 수익 HP를 만든다.
    private Long influencerWithRevenue(long amountHp) {
        return revenueWallet(INFLUENCER, amountHp);
    }

    private Long revenueWallet(Long userId, long amountHp) {
        Long buyerWallet = walletRepository.findByUserId(BUYER).map(Wallet::getId).orElseGet(() -> wallet(BUYER));
        Long walletId = walletRepository.findByUserId(userId).map(Wallet::getId).orElseGet(() -> wallet(userId));
        charge(buyerWallet, amountHp);
        long itemId = 1_000L + sequence.incrementAndGet();
        billingPort.hold(new HoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, itemId, amountHp));
        billingPort.payout(PayoutCommand.full(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, itemId,
                "PAYOUT-" + itemId, userId, SettlementSourceType.LECTURE_ORDER_ITEM, itemId));
        return walletId;
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만든다.
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

    private void assertWallet(Long walletId, long charge, long heldCharge, long revenue, long heldRevenue) {
        assertThat(walletRepository.findById(walletId).orElseThrow())
                .extracting(Wallet::getChargeBalanceHp, Wallet::getHeldChargeHp, Wallet::getRevenueBalanceHp,
                        Wallet::getHeldRevenueHp)
                .containsExactly(charge, heldCharge, revenue, heldRevenue);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    // 모든 환전 신청의 금액·보류·원장 정합성
    private void assertConsistent() {
        for (Map<String, Object> exchange : jdbcTemplate.queryForList("SELECT * FROM billing.exchange")) {
            Long id = (Long) exchange.get("id");
            long requested = (Long) exchange.get("requested_amount_hp");
            assertThat((Long) exchange.get("fee_amount_hp") + (Long) exchange.get("payout_amount_krw"))
                    .isEqualTo(requested);
            assertThat(exchange.get("fee_amount_hp")).isEqualTo(requested / 10);
            Map<String, Object> hold = holdOf(id);
            assertThat(exchange.get("point_hold_id")).isEqualTo(hold.get("id"));
            assertThat(hold).containsEntry("hold_type", "REVENUE_ONLY").containsEntry("original_revenue_hp", requested)
                    .containsEntry("original_charge_hp", 0L).containsEntry("held_charge_hp", 0L);
            long released = releasedRevenue((Long) hold.get("id"));
            switch ((String) exchange.get("status")) {
                case "REQUESTED" -> {
                    assertThat(hold).containsEntry("held_revenue_hp", requested);
                    assertThat(released).isZero();
                    assertThat(exchange.get("canceled_at")).isNull();
                }
                case "CANCELED" -> {
                    assertThat(hold).containsEntry("held_revenue_hp", 0L);
                    assertThat(released).isEqualTo(requested);
                    assertThat(exchange.get("canceled_at")).isNotNull();
                }
                default -> {
                }
            }
            // 환전 원장은 수익 HP만 움직이고, 보류 이동 합(가용 + 보류)은 0이다
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM billing.point_ledger
                    WHERE reference_type = 'EXCHANGE' AND reference_id = ? AND point_type <> 'REVENUE'""",
                    Integer.class, id)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(amount_hp), 0) FROM billing.point_ledger
                    WHERE reference_type = 'EXCHANGE' AND reference_id = ?""", Long.class, id)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM billing.exchange_account WHERE exchange_id = ?", Integer.class, id))
                    .isEqualTo(1);
        }
        for (Long walletId : jdbcTemplate.queryForList("SELECT id FROM billing.wallet", Long.class)) {
            assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        }
    }

    private void assertNothingCreated() {
        assertThat(exchangeCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.exchange_account", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_hold WHERE reference_type = 'EXCHANGE'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE reference_type = 'EXCHANGE'", Integer.class)).isZero();
    }

    private Map<String, Object> holdOf(Long exchangeId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM billing.point_hold WHERE reference_type = 'EXCHANGE' AND reference_id = ?", exchangeId);
    }

    private Map<String, Object> exchangeRow(Long exchangeId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.exchange WHERE id = ?", exchangeId);
    }

    private int exchangeCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.exchange", Integer.class);
    }

    private long releasedRevenue(Long holdId) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(revenue_amount_hp), 0) FROM billing.point_hold_detail
                WHERE point_hold_id = ? AND action_type = 'RELEASE'""", Long.class, holdId);
    }

    private int releaseDetailCount(Long exchangeId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_hold_detail
                WHERE point_hold_id = (SELECT point_hold_id FROM billing.exchange WHERE id = ?)
                  AND action_type = 'RELEASE'""", Integer.class, exchangeId);
    }

    // 환전 신청·보류·반환 원장 "HP종류 버킷 금액 거래유형"
    private List<String> ledgerOf(Long exchangeId) {
        return jdbcTemplate.queryForList("""
                SELECT point_type || ' ' || balance_bucket || ' ' || amount_hp || ' ' || transaction_type
                FROM billing.point_ledger WHERE reference_type = 'EXCHANGE' AND reference_id = ? ORDER BY id""",
                String.class, exchangeId);
    }

    // 작업을 동시에 시작해 결과(성공 값 또는 BillingErrorCode)를 모은다.
    private List<Object> runConcurrently(int count, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Object> callable = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.run(index);
                    } catch (BillingException exception) {
                        return exception.getErrorCode();
                    }
                };
                futures.add(executor.submit(callable));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertBillingError(Runnable action, BillingErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BillingException.class)
                .extracting(exception -> ((BillingException) exception).getErrorCode()).isEqualTo(code);
    }

    @FunctionalInterface
    private interface IndexedTask {
        Object run(int index);
    }
}
