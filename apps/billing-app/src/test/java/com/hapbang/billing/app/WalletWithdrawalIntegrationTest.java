package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.testsupport.IntegrationTest;

// 회원 탈퇴용 지갑 닫기·다시 열기(#623)를 실제 PostgreSQL로 검증한다: 막힌 이유, 멱등(replayed·다른 회원), 늦은 닫기 거절,
// 지갑이 없는 회원, 닫힌 지갑의 신청 거절과 이미 생긴 의무 처리, 동시 실행.
@IntegrationTest
// 내부 Facade(HoldFacade·WalletWithdrawalFacade)는 내부 호출(ROLE_INTERNAL)만 허용한다
@WithMockUser(roles = "INTERNAL")
@ExtendWith(OutputCaptureExtension.class)
class WalletWithdrawalIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final BillingReferenceType ORDER = BillingReferenceType.LECTURE_ORDER_ITEM;
    @Autowired
    WalletWithdrawalFacade walletWithdrawalFacade;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    WalletCreateUseCase walletCreateUseCase;

    @Autowired
    ChargeOrderUseCase chargeOrderUseCase;

    @Autowired
    RefundRequestUseCase refundRequestUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    ExchangeRepository exchangeRepository;

    @Autowired
    ClosedWalletBalanceMetrics closedWalletBalanceMetrics;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    // ---------------------------------------------------------------------------------------------
    // 닫기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("막힌 이유가 없으면 지갑을 닫고 CLOSED와 빈 이유를 돌려준다")
    void closed() {
        Long walletId = wallet(1L);

        WalletWithdrawalCloseResult result = walletWithdrawalFacade.close(100L, 1L);

        assertThat(result).isEqualTo(new WalletWithdrawalCloseResult(100L, WithdrawalCloseOutcome.CLOSED, List.of(),
                false));
        assertThat(walletOf(walletId).isClosedBy(100L)).isTrue();
        assertThat(walletOf(walletId).getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("충전·수익 HP 잔액이 남아 있으면 지갑을 닫지 않고 BLOCKED와 금액을 적은 이유를 돌려준다")
    void blockedByBalances() {
        Long walletId = wallet(1L);
        charge(walletId, 12_000L);
        revenue(walletId, 3_000L);

        WalletWithdrawalCloseResult result = walletWithdrawalFacade.close(100L, 1L);

        assertThat(result.outcome()).isEqualTo(WithdrawalCloseOutcome.BLOCKED);
        assertThat(result.reasons()).containsExactly(
                WithdrawalBlockReasonDto.of("BILLING_CHARGE_BALANCE_REMAINING",
                        "충전 HP 12,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요."),
                WithdrawalBlockReasonDto.of("BILLING_REVENUE_BALANCE_REMAINING",
                        "수익 HP 3,000이 남아 있어요. 환전을 신청한 뒤 탈퇴할 수 있어요."));
        assertThat(walletOf(walletId).isClosed()).isFalse();
    }

    @Test
    @DisplayName("처리 중인 환불·환전·확인 중인 충전이 있으면 BLOCKED이고 환전은 정산 예정일을 준다. 출연·강의 보류는 이유로 적지 않는다")
    void blockedByInProgress() {
        Long walletId = wallet(1L);
        charge(walletId, 5_000L);
        revenue(walletId, 1_000L);
        hold(1L, 1001L, 1_000L);  // 강의 보류 (수익 HP 1,000)
        refundRequestUseCase.create(1L, "refund-1", 5_000L);
        Long exchangeId = requestedExchange(1L, walletId);
        processingCharge(1L);

        WalletWithdrawalCloseResult result = walletWithdrawalFacade.close(100L, 1L);

        LocalDate payoutDate = jdbcTemplate.queryForObject(
                "SELECT payout_scheduled_at FROM billing.exchange WHERE id = ?", Timestamp.class, exchangeId)
                .toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        assertThat(result.outcome()).isEqualTo(WithdrawalCloseOutcome.BLOCKED);
        assertThat(result.reasons()).containsExactly(
                WithdrawalBlockReasonDto.of("BILLING_REFUND_IN_PROGRESS",
                        "처리 중인 환불이 있어요. 환불이 끝난 뒤 탈퇴할 수 있어요."),
                new WithdrawalBlockReasonDto("BILLING_EXCHANGE_IN_PROGRESS",
                        "처리 중인 환전이 있어요. 송금이 끝난 뒤 탈퇴할 수 있어요.", payoutDate),
                WithdrawalBlockReasonDto.of("BILLING_CHARGE_IN_PROGRESS",
                        "확인 중인 충전이 있어요. 잠시 뒤 다시 시도해 주세요."));
        assertThat(walletOf(walletId).isClosed()).isFalse();
    }

    @Test
    @DisplayName("주문만 한 충전(PENDING)은 막지 않는다")
    void pendingChargeDoesNotBlock() {
        wallet(1L);
        chargeOrderUseCase.create(1L, 10_000L);

        assertThat(walletWithdrawalFacade.close(100L, 1L).outcome()).isEqualTo(WithdrawalCloseOutcome.CLOSED);
    }

    @Test
    @DisplayName("같은 탈퇴 작업으로 다시 닫으면 처음 결과를 replayed로 돌려준다(CLOSED·BLOCKED 각각)")
    void closeReplay() {
        wallet(1L);
        Long blockedWalletId = wallet(2L);
        charge(blockedWalletId, 1_000L);
        WalletWithdrawalCloseResult closed = walletWithdrawalFacade.close(100L, 1L);
        WalletWithdrawalCloseResult blocked = walletWithdrawalFacade.close(200L, 2L);
        // 잔액이 바뀌어도 BLOCKED 재요청은 저장한 이유 그대로다
        charge(blockedWalletId, 1_000L);

        assertThat(walletWithdrawalFacade.close(100L, 1L)).isEqualTo(new WalletWithdrawalCloseResult(100L,
                WithdrawalCloseOutcome.CLOSED, List.of(), true));
        assertThat(walletWithdrawalFacade.close(200L, 2L)).isEqualTo(new WalletWithdrawalCloseResult(200L,
                WithdrawalCloseOutcome.BLOCKED, blocked.reasons(), true));
        assertThat(blocked.reasons()).extracting(WithdrawalBlockReasonDto::message)
                .containsExactly("충전 HP 1,000이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요.");
        assertThat(closed.replayed()).isFalse();
    }

    @Test
    @DisplayName("같은 탈퇴 작업 ID를 다른 회원이 보내면 닫기·다시 열기 모두 IDEMPOTENCY_KEY_CONFLICT다")
    void otherUser() {
        wallet(1L);
        Long otherWalletId = wallet(2L);
        walletWithdrawalFacade.close(100L, 1L);

        assertBillingError(() -> walletWithdrawalFacade.close(100L, 2L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> walletWithdrawalFacade.reopen(100L, 2L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(walletOf(otherWalletId).isClosed()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 다시 열기
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("닫힌 지갑을 다시 열면 REOPENED이고, 재요청은 replayed, 그 뒤의 닫기는 WITHDRAWAL_ALREADY_REOPENED다")
    void reopenClosed() {
        Long walletId = wallet(1L);
        walletWithdrawalFacade.close(100L, 1L);

        assertThat(walletWithdrawalFacade.reopen(100L, 1L)).isEqualTo(new WalletWithdrawalReopenResult(100L,
                WithdrawalReopenOutcome.REOPENED, false));
        assertThat(walletOf(walletId).isClosed()).isFalse();
        assertThat(walletWithdrawalFacade.reopen(100L, 1L)).isEqualTo(new WalletWithdrawalReopenResult(100L,
                WithdrawalReopenOutcome.REOPENED, true));
        assertBillingError(() -> walletWithdrawalFacade.close(100L, 1L),
                BillingErrorCode.WITHDRAWAL_ALREADY_REOPENED);
        assertThat(walletOf(walletId).isClosed()).isFalse();
    }

    @Test
    @DisplayName("닫기 전에 다시 열기를 받으면 NOT_CLOSED를 기록하고, 늦게 온 닫기는 지갑을 닫지 않고 거절한다")
    void reopenBeforeClose() {
        Long walletId = wallet(1L);

        assertThat(walletWithdrawalFacade.reopen(100L, 1L)).isEqualTo(new WalletWithdrawalReopenResult(100L,
                WithdrawalReopenOutcome.NOT_CLOSED, false));
        assertThat(walletWithdrawalFacade.reopen(100L, 1L).replayed()).isTrue();
        assertBillingError(() -> walletWithdrawalFacade.close(100L, 1L),
                BillingErrorCode.WITHDRAWAL_ALREADY_REOPENED);
        assertThat(walletOf(walletId).isClosed()).isFalse();
    }

    @Test
    @DisplayName("BLOCKED였던 탈퇴 작업의 다시 열기는 NOT_CLOSED이고 지갑을 바꾸지 않는다")
    void reopenBlocked() {
        Long walletId = wallet(1L);
        charge(walletId, 1_000L);
        walletWithdrawalFacade.close(100L, 1L);

        assertThat(walletWithdrawalFacade.reopen(100L, 1L).outcome()).isEqualTo(WithdrawalReopenOutcome.NOT_CLOSED);
        assertThat(walletOf(walletId).isClosed()).isFalse();
    }

    @Test
    @DisplayName("다른 탈퇴 작업으로 닫힌 지갑은 열지 않는다(NOT_CLOSED)")
    void reopenOtherWithdrawal() {
        Long walletId = wallet(1L);
        walletWithdrawalFacade.reopen(100L, 1L);
        walletWithdrawalFacade.close(101L, 1L);

        assertThat(walletWithdrawalFacade.reopen(102L, 1L).outcome()).isEqualTo(WithdrawalReopenOutcome.NOT_CLOSED);
        assertThat(walletOf(walletId).isClosedBy(101L)).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 지갑이 없는 회원
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("지갑이 없으면 기록만 남기고 CLOSED이며, 나중에 만든 지갑은 닫혀 있고 다시 열기로 열린다")
    void closeWithoutWallet() {
        assertThat(walletWithdrawalFacade.close(100L, 1L).outcome()).isEqualTo(WithdrawalCloseOutcome.CLOSED);
        assertThat(walletRepository.findByUserId(1L)).isEmpty();

        Wallet created = walletCreateUseCase.getOrCreate(1L);
        assertThat(walletOf(created.getId()).isClosedBy(100L)).isTrue();

        assertThat(walletWithdrawalFacade.reopen(100L, 1L).outcome()).isEqualTo(WithdrawalReopenOutcome.REOPENED);
        assertThat(walletOf(created.getId()).isClosed()).isFalse();
    }

    @Test
    @DisplayName("지갑이 없을 때 닫고 다시 열면 REOPENED이고, 나중에 만든 지갑은 열려 있다")
    void reopenWithoutWallet() {
        walletWithdrawalFacade.close(100L, 1L);

        assertThat(walletWithdrawalFacade.reopen(100L, 1L).outcome()).isEqualTo(WithdrawalReopenOutcome.REOPENED);
        assertThat(walletCreateUseCase.getOrCreate(1L).isClosed()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 닫힌 지갑
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("닫힌 지갑은 충전 주문·보류·환불·환전 신청을 WALLET_CLOSED로 거절한다")
    void closedWalletRejectsNewMoney() {
        Long walletId = wallet(1L);
        walletWithdrawalFacade.close(100L, 1L);

        // 충전 승인(PG 호출 전 거절)은 결제 설정이 있는 ChargeIntegrationTest가 확인한다
        assertBillingError(() -> chargeOrderUseCase.create(1L, 10_000L), BillingErrorCode.WALLET_CLOSED);
        assertBillingError(() -> hold(1L, 1001L, 1_000L), BillingErrorCode.WALLET_CLOSED);
        assertBillingError(() -> refundRequestUseCase.create(1L, "refund-1", 1_000L), BillingErrorCode.WALLET_CLOSED);
        assertBillingError(() -> exchangeRequestUseCase.create(1L, "exchange-1",
                new ExchangeCommand(1_000L, "004", "12345678901234", "홍길동")), BillingErrorCode.WALLET_CLOSED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold WHERE wallet_id = ?",
                Integer.class, walletId)).isZero();
    }

    @Test
    @DisplayName("닫힌 지갑도 이미 생긴 보류의 반환·재요청과 다른 회원이 보낸 지급은 받고, 들어온 HP를 warn 로그와 지표로 알린다")
    void closedWalletKeepsObligations(CapturedOutput output) {
        Long walletId = wallet(1L);
        charge(walletId, 1_000L);
        HoldResult hold = hold(1L, 1001L, 1_000L);
        Long payerWalletId = wallet(2L);
        charge(payerWalletId, 2_000L);
        hold(2L, 2001L, 2_000L);
        assertThat(walletWithdrawalFacade.close(100L, 1L).outcome()).isEqualTo(WithdrawalCloseOutcome.CLOSED);

        assertThat(hold(1L, 1001L, 1_000L).holdId()).isEqualTo(hold.holdId());
        holdFacade.release(PaymentReleaseCommand.full(1L, ORDER, 1001L, "R1"));
        holdFacade.payout(new PaymentPayoutCommand(2L, ORDER, 2001L, "P1", 1L, SettlementSourceType.LECTURE_ORDER_ITEM,
                2001L, null));

        Wallet closed = walletOf(walletId);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getChargeBalanceHp()).isEqualTo(1_000L);
        assertThat(closed.getRevenueBalanceHp()).isEqualTo(2_000L);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();

        // 닫힌 뒤 들어온 HP는 환불·환전할 수 없어 묶이므로 운영자가 알 수 있게 한다 (BD-141)
        assertThat(output).contains("닫힌 지갑에 HP가 들어왔다", "walletId=" + walletId, "closedByWithdrawalId=100");
        // 지표는 수집 때마다 DB에서 세므로 배치를 기다리지 않고 바로 보인다
        assertThat(closedWalletBalanceMetrics.count()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시 실행
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 회원의 보류 생성과 닫기가 동시에 와도 닫힌 지갑에 새 보류가 생기지 않는다")
    void concurrentHoldAndClose() throws Exception {
        for (long userId = 21L; userId <= 30L; userId++) {
            long user = userId;
            Long walletId = wallet(user);
            charge(walletId, 1_000L);
            hold(user, user * 1000, 1_000L);  // 잔액 0: 닫을 수 있다
            holdFacade.release(PaymentReleaseCommand.partial(user, ORDER, user * 1000, "R1", 500L));  // 잔액 500

            List<String> results = runTogether(List.of(
                    () -> {
                        try {
                            hold(user, user * 1000 + 1, 500L);
                            return "HELD";
                        } catch (BillingException exception) {
                            return exception.getErrorCode().name();
                        }
                    },
                    () -> walletWithdrawalFacade.close(user * 100, user).outcome().name()));

            // 보류가 먼저면 잔액 0이라 닫히고, 닫기가 먼저면 잔액이 남아 BLOCKED이며 보류가 성공한다
            assertThat(results).isIn(List.of("HELD", "CLOSED"), List.of("HELD", "BLOCKED"));
            assertThat(walletOf(walletId).getChargeBalanceHp()).isZero();
            assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        }
    }

    @Test
    @DisplayName("같은 탈퇴 작업의 닫기와 다시 열기가 동시에 와도 지갑이 닫힌 채 남지 않는다")
    void concurrentCloseAndReopen() throws Exception {
        for (long userId = 31L; userId <= 40L; userId++) {
            long user = userId;
            Long walletId = wallet(user);

            List<String> results = runTogether(List.of(
                    () -> {
                        try {
                            return walletWithdrawalFacade.close(user * 100, user).outcome().name();
                        } catch (BillingException exception) {
                            return exception.getErrorCode().name();
                        }
                    },
                    () -> walletWithdrawalFacade.reopen(user * 100, user).outcome().name()));

            assertThat(results).isIn(List.of("CLOSED", "REOPENED"), List.of("WITHDRAWAL_ALREADY_REOPENED", "NOT_CLOSED"));
            assertThat(walletOf(walletId).isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("지갑이 없는 회원의 닫기와 지갑 생성이 동시에 와도 지갑이 열린 채 남지 않는다")
    void concurrentCloseAndCreate() throws Exception {
        for (long userId = 41L; userId <= 50L; userId++) {
            long user = userId;

            runTogether(List.of(
                    () -> walletWithdrawalFacade.close(user * 100, user).outcome().name(),
                    () -> walletCreateUseCase.getOrCreate(user).getId().toString()));

            assertThat(walletRepository.findByUserId(user).orElseThrow().isClosedBy(user * 100)).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private HoldResult hold(Long userId, Long referenceId, long amountHp) {
        return holdFacade.hold(new PaymentHoldCommand(userId, ORDER, referenceId, amountHp));
    }

    // 신청(REQUESTED) 상태의 환전 기록. 막힌 이유는 환전 상태만 보므로 보류·계좌(암호화 키 필요)는 만들지 않는다
    private Long requestedExchange(Long userId, Long walletId) {
        Instant now = Instant.now();
        return exchangeRepository.save(Exchange.requested(userId, walletId, 1_000L,
                "exchange-" + sequence.incrementAndGet(), ExchangeSettlementRound.of(now), now)).getId();
    }

    // 충전 주문을 만들고 PG 승인 결과를 확인 중(PROCESSING)인 상태로 바꾼다
    private void processingCharge(Long userId) {
        ChargeResult order = chargeOrderUseCase.create(userId, 10_000L);
        jdbcTemplate.update("UPDATE billing.point_charge SET status = ? WHERE pg_order_id = ?",
                PointChargeStatus.PROCESSING.name(), order.orderId());
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    private Wallet walletOf(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow();
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

    private void assertBillingError(Runnable action, BillingErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BillingException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    // 모든 작업을 같은 시점에 시작시키고 결과를 모으며, 작업 예외는 그대로 드러낸다.
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(tasks.size()));
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
