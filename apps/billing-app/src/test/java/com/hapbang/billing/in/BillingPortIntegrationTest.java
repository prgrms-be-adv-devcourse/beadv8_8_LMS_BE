package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.testsupport.IntegrationTest;

// 강의·출연이 쓰는 shared BillingPort를 실제 Spring 빈으로 주입해, 외부 계약 결과 변환과 호출한 쪽 트랜잭션 참여(커밋·롤백)를
// 실제 PostgreSQL로 검증한다. 호출한 쪽 업무 상태는 테스트 동안만 만드는 테이블(billing.test_caller_state)의 행으로 흉내 낸다.
@IntegrationTest
class BillingPortIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType REQUEST_SOURCE = SettlementSourceType.APPEARANCE_REQUEST;
    private static final long HOST = 1L;
    private static final long GUEST = 2L;

    @Autowired
    BillingPort billingPort;

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

    @BeforeEach
    void createCallerTable() {
        jdbcTemplate.execute("CREATE TABLE billing.test_caller_state (id BIGINT PRIMARY KEY, status TEXT NOT NULL)");
    }

    @AfterEach
    void dropCallerTable() {
        jdbcTemplate.execute("DROP TABLE billing.test_caller_state");
    }

    // ---------------------------------------------------------------------------------------------
    // 빈과 결과 변환
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BillingPort는 Billing의 BillingPortAdapter 빈 하나로 주입된다")
    void portBean() {
        assertThat(billingPort).isInstanceOf(BillingPortAdapter.class);
    }

    @Test
    @DisplayName("보류 결과는 참조 대상·보류액·재요청 여부만 담고, 같은 요청은 재요청, 다른 금액·다른 회원은 충돌이다")
    void hold() {
        Long host = wallet(HOST);
        wallet(GUEST);
        revenue(host, 1_000L);
        charge(host, 9_000L, T1);

        HoldResultDto held = billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
        HoldResultDto retried = billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));

        assertThat(held).isEqualTo(new HoldResultDto(REQUEST, 7L, 10_000L, false));
        assertThat(retried).isEqualTo(new HoldResultDto(REQUEST, 7L, 10_000L, true));
        assertBillingError(() -> billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 9_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> billingPort.hold(new HoldCommand(GUEST, REQUEST, 7L, 10_000L)),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(holdCount()).isEqualTo(1);
        assertReconciled(host);
    }

    @Test
    @DisplayName("강의는 주문 항목마다 보류하고, 한 항목을 반환해도 같은 주문의 다른 항목 보류는 그대로다")
    void lectureOrderItems() {
        Long buyer = wallet(HOST);
        charge(buyer, 30_000L, T1);

        billingPort.hold(new HoldCommand(HOST, ITEM, 5001L, 10_000L));
        billingPort.hold(new HoldCommand(HOST, ITEM, 5002L, 20_000L));
        ReleaseResultDto canceled = billingPort.release(ReleaseCommand.full(HOST, ITEM, 5001L, "ORDER-ITEM-5001-CANCEL"));

        assertThat(canceled).isEqualTo(new ReleaseResultDto(ITEM, 5001L, "ORDER-ITEM-5001-CANCEL", 10_000L, 0L, false));
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key || ' ' || (held_charge_hp + held_revenue_hp) FROM billing.point_hold ORDER BY id",
                String.class)).containsExactly("HOLD:LECTURE_ORDER_ITEM:5001 0", "HOLD:LECTURE_ORDER_ITEM:5002 20000");
        assertReconciled(buyer);
    }

    @Test
    @DisplayName("반환·지급 결과는 처리 금액과 응답 시점의 남은 보류액을 담고, 같은 요청 ID 재요청은 처음 결과다")
    void releaseAndPayout() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 20_000L, T1);
        billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 20_000L));

        ReleaseResultDto released = billingPort.release(ReleaseCommand.partial(HOST, REQUEST, 7L, "R1", 5_000L));
        PayoutResultDto paid = billingPort.payout(
                PayoutCommand.full(HOST, REQUEST, 7L, "CONFIRM", GUEST, REQUEST_SOURCE, 7001L));
        ReleaseResultDto releaseRetried = billingPort.release(ReleaseCommand.partial(HOST, REQUEST, 7L, "R1", 5_000L));
        PayoutResultDto payoutRetried = billingPort.payout(
                PayoutCommand.full(HOST, REQUEST, 7L, "CONFIRM", GUEST, REQUEST_SOURCE, 7001L));

        assertThat(released).isEqualTo(new ReleaseResultDto(REQUEST, 7L, "R1", 5_000L, 15_000L, false));
        assertThat(paid).isEqualTo(
                new PayoutResultDto(REQUEST, 7L, "CONFIRM", GUEST, REQUEST_SOURCE, 7001L, 15_000L, 0L, false));
        // 반환액은 처리 당시 값이고 남은 보류액은 응답 시점 값이다.
        assertThat(releaseRetried).isEqualTo(new ReleaseResultDto(REQUEST, 7L, "R1", 5_000L, 0L, true));
        assertThat(payoutRetried).isEqualTo(
                new PayoutResultDto(REQUEST, 7L, "CONFIRM", GUEST, REQUEST_SOURCE, 7001L, 15_000L, 0L, true));
        assertReconciled(host);
        assertReconciled(guest);
    }

    @Test
    @DisplayName("위약금 정산 결과는 출연이 보낸 반환액·지급액과 남은 보류액을 담는다")
    void settlePenalty() {
        Long host = wallet(HOST);
        Long guest = wallet(GUEST);
        charge(host, 4_000L, T1);
        charge(host, 6_000L, T2);
        billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
        PenaltyCommand command = new PenaltyCommand(HOST, REQUEST, 7L, "CANCEL-1", GUEST, REQUEST_SOURCE, 7001L,
                7_000L, 3_000L);

        PenaltyResultDto settled = billingPort.settlePenalty(command);
        PenaltyResultDto retried = billingPort.settlePenalty(command);

        assertThat(settled).isEqualTo(new PenaltyResultDto(REQUEST, 7L, "CANCEL-1", GUEST, REQUEST_SOURCE, 7001L,
                7_000L, 3_000L, 0L, false));
        assertThat(retried).isEqualTo(new PenaltyResultDto(REQUEST, 7L, "CANCEL-1", GUEST, REQUEST_SOURCE, 7001L,
                7_000L, 3_000L, 0L, true));
        assertBalances(host, 7_000L, 0L, 0L, 0L);
        assertBalances(guest, 0L, 3_000L, 0L, 0L);
        assertReconciled(host);
        assertReconciled(guest);
    }

    @Test
    @DisplayName("잔액 부족은 결과값이 아니라 BillingException(INSUFFICIENT_BALANCE, 409)으로 알리고 보류를 만들지 않는다")
    void insufficientBalanceIsException() {
        Long host = wallet(HOST);
        charge(host, 5_000L, T1);

        assertThatThrownBy(() -> billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 5_001L)))
                .isInstanceOfSatisfying(BillingException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE);
                    assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(holdCount()).isZero();
    }

    @Test
    @DisplayName("금액 검증은 DTO 이동 전과 같은 오류 코드다: 0 HP 보류는 INVALID_POINT_AMOUNT, 위약금 0은 PENALTY_AMOUNT_ZERO(400)")
    void amountErrorsUnchanged() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 5_000L, T1);
        billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 5_000L));

        assertBillingError(() -> billingPort.hold(new HoldCommand(HOST, REQUEST, 8L, 0L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> billingPort.release(ReleaseCommand.partial(HOST, REQUEST, 7L, "R1", 0L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> billingPort.payout(
                        PayoutCommand.partial(HOST, REQUEST, 7L, "P1", GUEST, REQUEST_SOURCE, 7001L, -1L)),
                BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> billingPort.settlePenalty(
                        new PenaltyCommand(HOST, REQUEST, 7L, "C1", GUEST, REQUEST_SOURCE, 7001L, 5_000L, 0L)),
                BillingErrorCode.PENALTY_AMOUNT_ZERO);
        assertThat(BillingErrorCode.INVALID_POINT_AMOUNT.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(BillingErrorCode.PENALTY_AMOUNT_ZERO.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertReconciled(host);
    }

    // ---------------------------------------------------------------------------------------------
    // 호출한 쪽 트랜잭션
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("호출한 쪽이 업무 상태를 바꾼 트랜잭션에서 Billing이 성공하면 업무 상태와 보류가 함께 커밋된다")
    void callerTransaction_commitsTogether() {
        Long host = wallet(HOST);
        charge(host, 10_000L, T1);

        inCallerTransaction(() -> {
            jdbcTemplate.update("INSERT INTO billing.test_caller_state (id, status) VALUES (7, 'REQUESTED')");
            billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
        });

        assertThat(callerStates()).containsExactly("7 REQUESTED");
        assertThat(holdCount()).isEqualTo(1);
        assertBalances(host, 0L, 0L, 10_000L, 0L);
        assertReconciled(host);
    }

    @Test
    @DisplayName("Billing이 잔액 부족으로 실패하면 같은 트랜잭션에서 먼저 바꾼 호출한 쪽 업무 상태도 롤백된다")
    void callerTransaction_billingFailureRollsBackCaller() {
        Long host = wallet(HOST);
        charge(host, 5_000L, T1);
        Map<String, Object> before = snapshot();

        assertThatThrownBy(() -> inCallerTransaction(() -> {
            jdbcTemplate.update("INSERT INTO billing.test_caller_state (id, status) VALUES (7, 'REQUESTED')");
            billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
        })).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));

        assertThat(callerStates()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("Billing 성공 뒤 호출한 쪽이 실패하면 Billing의 보류도 함께 롤백된다(Billing이 따로 커밋하지 않는다)")
    void callerTransaction_callerFailureRollsBackBilling() {
        Long host = wallet(HOST);
        charge(host, 10_000L, T1);
        Map<String, Object> before = snapshot();

        assertThatThrownBy(() -> inCallerTransaction(() -> {
            jdbcTemplate.update("INSERT INTO billing.test_caller_state (id, status) VALUES (7, 'REQUESTED')");
            billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
            throw new IllegalStateException("이후 업무 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(callerStates()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("위약금 정산에서 반환은 성공하고 지급이 실패하면 반환과 호출한 쪽 취소 상태까지 모두 롤백된다")
    void callerTransaction_penaltyPayoutFailureRollsBackAll() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 10_000L, T1);
        billingPort.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));
        jdbcTemplate.update("INSERT INTO billing.test_caller_state (id, status) VALUES (7, 'CONFIRMED')");
        Map<String, Object> before = snapshot();

        failOnPayoutDetail(() -> assertThatThrownBy(() -> inCallerTransaction(() -> {
            jdbcTemplate.update("UPDATE billing.test_caller_state SET status = 'HOST_CANCELED' WHERE id = 7");
            billingPort.settlePenalty(new PenaltyCommand(HOST, REQUEST, 7L, "CANCEL-1", GUEST, REQUEST_SOURCE, 7001L,
                    7_000L, 3_000L));
        })).hasStackTraceContaining("test failure"));

        assertThat(callerStates()).containsExactly("7 CONFIRMED");
        assertThat(snapshot()).isEqualTo(before);
        assertReconciled(host);
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    // 강의·출연 서비스의 @Transactional 메서드처럼 바깥 트랜잭션을 열고 그 안에서 실행한다.
    private void inCallerTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다.
    private Long charge(Long walletId, long amountHp, Instant completedAt) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + sequence.incrementAndGet(), Timestamp.from(T1),
                Timestamp.from(completedAt));
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

    // 지급 상세 INSERT에 실패 트리거를 잠시 걸고 동작을 실행한 뒤 지운다(반환 상세는 저장된 뒤 지급 상세에서 실패한다).
    private void failOnPayoutDetail(Runnable action) {
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test failure';
                END;
                $$""");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail BEFORE INSERT ON billing.point_hold_detail FOR EACH ROW
                WHEN (NEW.action_type = 'PAYOUT') EXECUTE FUNCTION billing.test_fail()""");
        try {
            action.run();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_fail ON billing.point_hold_detail");
            jdbcTemplate.execute("DROP FUNCTION billing.test_fail()");
        }
    }

    private List<String> callerStates() {
        return jdbcTemplate.queryForList("SELECT id || ' ' || status FROM billing.test_caller_state ORDER BY id",
                String.class);
    }

    private int holdCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class);
    }

    // 지갑·충전 원금·보류·상세·배분·지급 건·원장 상태를 비교할 수 있게 모은다.
    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("charges", jdbcTemplate.queryForList(
                "SELECT id, remaining_principal_hp FROM billing.point_charge ORDER BY id"));
        snapshot.put("holds", jdbcTemplate.queryForList(
                "SELECT id, held_charge_hp, held_revenue_hp, status FROM billing.point_hold ORDER BY id"));
        snapshot.put("details", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("usages", jdbcTemplate.queryForList(
                "SELECT id, remaining_held_hp, consumed_hp, released_hp FROM billing.point_charge_usage ORDER BY id"));
        snapshot.put("settlements", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key FROM billing.settlement ORDER BY id"));
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

    // 지갑 캐시 잔액이 원장 합계와 같은지 확인한다.
    private void assertReconciled(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("원장 대사").isTrue();
    }

    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
