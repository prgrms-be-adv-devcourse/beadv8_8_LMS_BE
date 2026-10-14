package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.SettlementStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.testsupport.IntegrationTest;

// 강의 주문 항목별 판매 수익 지급 예정 건의 등록·멱등성·동시성·상태 전이와, 등록이 HP를 옮기지 않음을 실제 PostgreSQL로 검증한다(#142).
@IntegrationTest
class LectureSettlementIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    // 2026-10-13 15:00 KST 구매 → 2026-11-13 00:00 KST(= 2026-11-12 15:00 UTC)부터 지급
    private static final Instant PURCHASED_AT = LocalDateTime.parse("2026-10-13T15:00:00").atZone(KST).toInstant();
    private static final Instant PAYABLE_AT = Instant.parse("2026-11-12T15:00:00Z");
    private static final BillingReferenceType ITEM = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final long BUYER = 1L;
    private static final long SELLER = 2L;
    private static final long ORDER_ITEM = 5001L;
    private static final int THREADS = 8;

    @Autowired
    LectureSettlementFacade lectureSettlementFacade;

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

    // ---------------------------------------------------------------------------------------------
    // 등록
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("주문 항목 보류가 있으면 판매자 지갑으로 결제 HP 전액의 SCHEDULED 지급 건을 만들고, HP는 움직이지 않는다")
    void schedule() {
        Long buyer = wallet(BUYER);
        Long seller = wallet(SELLER);
        revenue(buyer, 3_000L);
        charge(buyer, 7_000L);
        HoldResult hold = hold(ORDER_ITEM, 10_000L);
        Map<String, Object> before = financialSnapshot();

        LectureSettlementResult result = schedule(ORDER_ITEM, SELLER, 10_000L);

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(result.holdId()).isEqualTo(hold.holdId());
        assertThat(result.receiverUserId()).isEqualTo(SELLER);
        assertThat(result.receiverWalletId()).isEqualTo(seller);
        assertThat(result.amountHp()).isEqualTo(10_000L);
        assertThat(result.scheduledAt()).isEqualTo(PAYABLE_AT);
        assertThat(settlementRows()).containsExactly("SETTLEMENT:LECTURE_ORDER_ITEM:5001 " + SELLER + " " + seller + " "
                + hold.holdId() + " LECTURE_ORDER_ITEM 5001 NORMAL 10000 0 SCHEDULED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scheduled_at FROM billing.settlement WHERE source_id = ?", Timestamp.class, ORDER_ITEM)
                .toInstant()).isEqualTo(PAYABLE_AT);
        // 지갑·보류·배분·상세·원장은 그대로다(새 HP 이동·중복 차감 없음).
        assertThat(financialSnapshot()).isEqualTo(before);
        assertReconciled(buyer);
        assertReconciled(seller);
    }

    @Test
    @DisplayName("주문 항목마다 보류·지급 건이 따로 있고, 한 항목의 등록이 다른 항목에 영향을 주지 않는다")
    void independentItems() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        wallet(3L);
        charge(buyer, 30_000L);
        hold(5001L, 10_000L);
        hold(5002L, 20_000L);

        schedule(5001L, SELLER, 10_000L);
        schedule(5002L, 3L, 20_000L);
        lectureSettlementFacade.cancel(5001L);

        assertThat(jdbcTemplate.queryForList(
                "SELECT source_id || ' ' || receiver_user_id || ' ' || gross_amount_hp || ' ' || status FROM billing.settlement ORDER BY source_id",
                String.class)).containsExactly("5001 2 10000 CANCELLED", "5002 3 20000 SCHEDULED");
    }

    @Test
    @DisplayName("금액이 0 이하이거나 보류액과 다르면, 지갑·보류가 맞지 않으면 거절하고 지급 건을 만들지 않는다")
    void rejects() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        wallet(3L);
        charge(buyer, 20_000L);
        hold(ORDER_ITEM, 10_000L);
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 0L), BillingErrorCode.INVALID_POINT_AMOUNT);
        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 9_999L), BillingErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 10_001L), BillingErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        // 판매자 지갑이 없으면 만들지 않고 거절한다.
        assertBillingError(() -> schedule(ORDER_ITEM, 99L, 10_000L), BillingErrorCode.WALLET_NOT_FOUND);
        // 구매자 = 판매자이면 HP 간 전환이 되므로 거절한다.
        assertBillingError(() -> schedule(ORDER_ITEM, BUYER, 10_000L), BillingErrorCode.PAYOUT_TO_PAYER);
        // 주문 항목 보류가 없거나, 요청한 구매자의 보류가 아니면 거절한다.
        assertBillingError(() -> schedule(7001L, SELLER, 10_000L), BillingErrorCode.HOLD_NOT_FOUND);
        assertBillingError(() -> lectureSettlementFacade.schedule(
                new LectureSettlementCommand(3L, SELLER, ORDER_ITEM, 10_000L, PURCHASED_AT)),
                BillingErrorCode.HOLD_OWNER_MISMATCH);

        assertThat(settlementCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.wallet WHERE user_id = 99", Integer.class))
                .isZero();
        // 실패한 등록은 지갑·충전 원금·보류·배분·상세·원장을 바꾸지 않는다.
        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("보류 멱등키는 이 주문 항목인데 보류의 참조 ID가 다른 행(비정상 데이터)이면 다른 주문 항목의 보류로 보고 거절한다")
    void holdReferenceMismatch() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        HoldResult hold = hold(ORDER_ITEM, 10_000L);
        jdbcTemplate.update("UPDATE billing.point_hold SET reference_id = 5009 WHERE id = ?", hold.holdId());

        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 10_000L), BillingErrorCode.PAYOUT_SOURCE_MISMATCH);
        assertThat(settlementCount()).isZero();
    }

    @Test
    @DisplayName("반환·지급 이력이 있는 보류에는 지급 예정 건을 만들지 않는다(보류의 최초·남은 보류액이 모두 결제 HP와 같아야 한다)")
    void holdWithHistory() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 20_000L);
        hold(5001L, 10_000L);
        hold(5002L, 10_000L);
        billingPort.release(ReleaseCommand.partial(BUYER, ITEM, 5001L, "PARTIAL", 1_000L));
        billingPort.payout(PayoutCommand.partial(BUYER, ITEM, 5002L, "EARLY", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, 5002L, 1_000L));
        Map<String, Object> before = financialSnapshot();
        List<String> settlementsBefore = settlementRows();

        // 부분 반환된 보류: 최초 금액으로도, 남은 금액으로도 등록할 수 없다.
        assertBillingError(() -> schedule(5001L, SELLER, 10_000L), BillingErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        assertBillingError(() -> schedule(5001L, SELLER, 9_000L), BillingErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        // 즉시 지급된 주문 항목: 이미 같은 원천의 완료 지급 건이 있어 다른 내용의 등록은 충돌이다.
        assertBillingError(() -> schedule(5002L, SELLER, 10_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementRows()).isEqualTo(settlementsBefore);
    }

    @Test
    @DisplayName("이미 반환된 보류(구매 취소)에는 지급 예정 건을 만들지 않는다")
    void closedHold() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        billingPort.release(ReleaseCommand.full(BUYER, ITEM, ORDER_ITEM, "CANCEL"));

        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 10_000L), BillingErrorCode.HOLD_ALREADY_CLOSED);
        assertThat(settlementCount()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 멱등성·동시성
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("같은 내용으로 다시 등록하면 기존 건을 돌려주고, 같은 주문 항목에 다른 금액·판매자·구매 시각은 충돌이다")
    void idempotency() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        wallet(3L);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        LectureSettlementResult first = schedule(ORDER_ITEM, SELLER, 10_000L);
        Map<String, Object> before = financialSnapshot();
        List<String> settlementsBefore = settlementRows();

        LectureSettlementResult retried = schedule(ORDER_ITEM, SELLER, 10_000L);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 9_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> schedule(ORDER_ITEM, 3L, 10_000L), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertBillingError(() -> lectureSettlementFacade.schedule(new LectureSettlementCommand(BUYER, SELLER,
                ORDER_ITEM, 10_000L, PURCHASED_AT.plusSeconds(86_400))), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        // 같은 KST 날짜 안의 다른 시각은 예정 시각이 같으므로 같은 요청이다.
        assertThat(lectureSettlementFacade.schedule(new LectureSettlementCommand(BUYER, SELLER, ORDER_ITEM, 10_000L,
                PURCHASED_AT.plusSeconds(3_600))).replayed()).isTrue();
        assertThat(settlementRows()).isEqualTo(settlementsBefore);
        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("취소된 지급 건은 같은 주문 항목으로 다시 등록하거나 되살리지 않는다")
    void cancelledNotReRegistered() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        lectureSettlementFacade.cancel(ORDER_ITEM);

        assertBillingError(() -> schedule(ORDER_ITEM, SELLER, 10_000L), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertBillingError(() -> lectureSettlementFacade.resume(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertThat(settlementRows()).hasSize(1).allMatch(row -> row.endsWith("CANCELLED"));
    }

    @Test
    @DisplayName("같은 등록 요청이 동시에 여러 번 들어와도 지급 건은 하나만 생기고 나머지는 같은 결과를 돌려받는다")
    void concurrentSameRegistration() throws Exception {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        Map<String, Object> before = financialSnapshot();
        List<Callable<LectureSettlementResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> schedule(ORDER_ITEM, SELLER, 10_000L));
        }

        List<LectureSettlementResult> results = runTogether(tasks);

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(LectureSettlementResult::settlementId).containsOnly(results.getFirst().settlementId());
        assertThat(settlementCount()).isEqualTo(1);
        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 주문 항목에 서로 다른 판매자 등록이 동시에 들어오면 하나만 성공하고 나머지는 충돌로 거절된다")
    void concurrentConflictingRegistration() throws Exception {
        Long buyer = wallet(BUYER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long seller = 100L + i;
            wallet(seller);
            tasks.add(() -> {
                try {
                    schedule(ORDER_ITEM, seller, 10_000L);
                    return true;
                } catch (BillingException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    return false;
                }
            });
        }

        List<Boolean> results = runTogether(tasks);

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("원천 UNIQUE(source_type, source_id)는 그대로라 같은 주문 항목의 두 번째 지급 건 행은 DB가 거절한다")
    void sourceUniqueKept() {
        Long buyer = wallet(BUYER);
        Long seller = wallet(SELLER);
        charge(buyer, 10_000L);
        HoldResult hold = hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);

        // 지급 유형을 바꿔 (보류, 원천, 지급 유형) UNIQUE를 피해도 원천 UNIQUE가 막는다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO billing.settlement (receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                    payout_type, gross_amount_hp, paid_amount_hp, status, idempotency_key, scheduled_at, created_at, updated_at)
                VALUES (?, ?, ?, 'LECTURE_ORDER_ITEM', ?, 'PENALTY', 10000, 0, 'SCHEDULED', 'OTHER-KEY', now(), now(), now())""",
                SELLER, seller, hold.holdId(), ORDER_ITEM)).hasMessageContaining("uk_settlement_source_type_source_id");
    }

    // ---------------------------------------------------------------------------------------------
    // 상태 전이·지급 차단
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("이의 제기로 중단하면 예정 시각이 지나도 지급할 수 없고, 재개하면 처음 예정 시각으로 다시 지급할 수 있다")
    void suspendBlocksPayoutAndResumeKeepsSchedule() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        Instant afterSchedule = PAYABLE_AT.plusSeconds(86_400 * 10);

        assertThat(lectureSettlementFacade.isPayable(ORDER_ITEM, PAYABLE_AT.minusNanos(1000))).isFalse();
        assertThat(lectureSettlementFacade.isPayable(ORDER_ITEM, PAYABLE_AT)).isTrue();

        LectureSettlementResult suspended = lectureSettlementFacade.suspend(ORDER_ITEM);
        assertThat(suspended.status()).isEqualTo(SettlementStatus.SUSPENDED);
        assertThat(suspended.replayed()).isFalse();
        assertThat(lectureSettlementFacade.suspend(ORDER_ITEM).replayed()).isTrue();
        assertThat(lectureSettlementFacade.isPayable(ORDER_ITEM, afterSchedule)).isFalse();

        LectureSettlementResult resumed = lectureSettlementFacade.resume(ORDER_ITEM);
        assertThat(resumed.status()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(resumed.scheduledAt()).isEqualTo(PAYABLE_AT);
        assertThat(lectureSettlementFacade.isPayable(ORDER_ITEM, afterSchedule)).isTrue();
        assertThat(settlementRows()).allMatch(row -> row.endsWith("SCHEDULED"));
    }

    @Test
    @DisplayName("SCHEDULED·SUSPENDED 건은 취소할 수 있고 취소는 다시 보내도 바꾸지 않으며, 행은 지우지 않는다")
    void cancelTransitions() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 20_000L);
        hold(5001L, 10_000L);
        hold(5002L, 10_000L);
        schedule(5001L, SELLER, 10_000L);
        schedule(5002L, SELLER, 10_000L);
        lectureSettlementFacade.suspend(5002L);

        assertThat(lectureSettlementFacade.cancel(5001L).status()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(lectureSettlementFacade.cancel(5002L).status()).isEqualTo(SettlementStatus.CANCELLED);
        assertThat(lectureSettlementFacade.cancel(5001L).replayed()).isTrue();
        assertBillingError(() -> lectureSettlementFacade.suspend(5001L), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertThat(lectureSettlementFacade.isPayable(5001L, PAYABLE_AT.plusSeconds(1))).isFalse();
        assertThat(settlementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("지급 완료(COMPLETED) 건은 취소·중단·재개할 수 없다")
    void completedIsFinal() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        // 실제 지급은 후속 Issue라, 완료 상태만 Fixture로 만든다.
        jdbcTemplate.update("""
                UPDATE billing.settlement SET status = 'COMPLETED', paid_amount_hp = gross_amount_hp, settled_at = now()
                WHERE source_id = ?""", ORDER_ITEM);

        assertBillingError(() -> lectureSettlementFacade.cancel(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertBillingError(() -> lectureSettlementFacade.suspend(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertBillingError(() -> lectureSettlementFacade.resume(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertThat(settlementRows()).allMatch(row -> row.endsWith("10000 10000 COMPLETED"));
        // 같은 등록 요청은 완료 건을 그대로 돌려준다(새 행을 만들지 않는다).
        assertThat(schedule(ORDER_ITEM, SELLER, 10_000L).status()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("지급 건이 없는 주문 항목의 상태 변경은 SETTLEMENT_NOT_FOUND다")
    void notFound() {
        assertBillingError(() -> lectureSettlementFacade.suspend(ORDER_ITEM), BillingErrorCode.SETTLEMENT_NOT_FOUND);
        assertBillingError(() -> lectureSettlementFacade.cancel(ORDER_ITEM), BillingErrorCode.SETTLEMENT_NOT_FOUND);
        assertBillingError(() -> lectureSettlementFacade.isPayable(ORDER_ITEM, PAYABLE_AT),
                BillingErrorCode.SETTLEMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 주문 항목의 중단과 취소가 동시에 와도 지급 건 락으로 직렬화되어 최종 상태는 CANCELLED다")
    void concurrentSuspendAndCancel() throws Exception {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean cancel = i % 2 == 0;
            tasks.add(() -> {
                try {
                    return (cancel ? lectureSettlementFacade.cancel(ORDER_ITEM)
                            : lectureSettlementFacade.suspend(ORDER_ITEM)).status().name();
                } catch (BillingException exception) {
                    // 취소가 먼저 끝난 뒤의 중단 요청만 거절된다.
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.SETTLEMENT_INVALID_STATE);
                    return "REJECTED";
                }
            });
        }

        runTogether(tasks);

        assertThat(settlementRows()).hasSize(1).allMatch(row -> row.endsWith("CANCELLED"));
    }

    @Test
    @DisplayName("중단된 건에 재개와 취소가 동시에 와도 최종 상태는 CANCELLED이고, 취소 뒤에는 재개·중단으로 되살릴 수 없다")
    void concurrentResumeAndCancel() throws Exception {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        lectureSettlementFacade.suspend(ORDER_ITEM);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean cancel = i % 2 == 0;
            tasks.add(() -> {
                try {
                    return (cancel ? lectureSettlementFacade.cancel(ORDER_ITEM)
                            : lectureSettlementFacade.resume(ORDER_ITEM)).status().name();
                } catch (BillingException exception) {
                    // 취소가 먼저 끝난 뒤의 재개 요청만 거절된다.
                    assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.SETTLEMENT_INVALID_STATE);
                    return "REJECTED";
                }
            });
        }

        runTogether(tasks);

        assertThat(settlementRows()).hasSize(1).allMatch(row -> row.endsWith("CANCELLED"));
        assertBillingError(() -> lectureSettlementFacade.resume(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertBillingError(() -> lectureSettlementFacade.suspend(ORDER_ITEM), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertThat(lectureSettlementFacade.cancel(ORDER_ITEM).replayed()).isTrue();
        assertThat(lectureSettlementFacade.isPayable(ORDER_ITEM, PAYABLE_AT.plusSeconds(1))).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 기존 지급·반환과의 관계, 트랜잭션
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("지급 예정 건이 있는 주문 항목은 기존 즉시 지급(BillingPort.payout)으로 우회해 지급할 수 없다")
    void immediatePayoutBlocked() {
        Long buyer = wallet(BUYER);
        Long seller = wallet(SELLER);
        charge(buyer, 10_000L);
        hold(ORDER_ITEM, 10_000L);
        schedule(ORDER_ITEM, SELLER, 10_000L);
        Map<String, Object> before = financialSnapshot();

        assertBillingError(() -> billingPort.payout(PayoutCommand.full(BUYER, ITEM, ORDER_ITEM, "PAY", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, ORDER_ITEM)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);

        assertThat(financialSnapshot()).isEqualTo(before);
        assertReconciled(seller);
    }

    @Test
    @DisplayName("중단·취소된 지급 건도 즉시 지급·위약금 정산으로 우회할 수 없고, 같은 원천의 새 지급 건 행을 만들지 않는다")
    void immediatePayoutBlockedWhenSuspendedOrCancelled() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 20_000L);
        hold(5001L, 10_000L);
        hold(5002L, 10_000L);
        schedule(5001L, SELLER, 10_000L);
        schedule(5002L, SELLER, 10_000L);
        lectureSettlementFacade.suspend(5001L);
        // 반환 없이 취소만 한 상태(보류는 남아 있다)에서도 지급하지 않는다.
        lectureSettlementFacade.cancel(5002L);
        Map<String, Object> before = financialSnapshot();
        List<String> settlementsBefore = settlementRows();

        for (long orderItemId : List.of(5001L, 5002L)) {
            assertBillingError(() -> billingPort.payout(PayoutCommand.full(BUYER, ITEM, orderItemId, "PAY", SELLER,
                    SettlementSourceType.LECTURE_ORDER_ITEM, orderItemId)), BillingErrorCode.PAYOUT_ALREADY_SETTLED);
            assertBillingError(() -> billingPort.settlePenalty(new PenaltyCommand(BUYER, ITEM, orderItemId, "PENALTY",
                    SELLER, SettlementSourceType.LECTURE_ORDER_ITEM, orderItemId, 0L, 10_000L)),
                    BillingErrorCode.PAYOUT_ALREADY_SETTLED);
        }

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementRows()).isEqualTo(settlementsBefore);
    }

    @Test
    @DisplayName("구매 취소는 한 트랜잭션에서 보류 반환과 지급 건 취소를 함께 커밋하고, 뒤 단계가 실패하면 둘 다 롤백된다")
    void cancelWithReleaseInOneTransaction() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 20_000L);
        hold(5001L, 10_000L);
        hold(5002L, 10_000L);
        schedule(5001L, SELLER, 10_000L);
        schedule(5002L, SELLER, 10_000L);
        Map<String, Object> before = financialSnapshot();
        List<String> settlementsBefore = settlementRows();

        // 실패: 반환·취소 뒤 강의 쪽 처리가 실패하면 반환과 취소가 모두 롤백된다.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            billingPort.release(ReleaseCommand.full(BUYER, ITEM, 5001L, "ORDER-ITEM-5001-CANCEL"));
            lectureSettlementFacade.cancel(5001L);
            throw new IllegalStateException("주문 상태 변경 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementRows()).isEqualTo(settlementsBefore);

        // 성공: 반환과 취소가 함께 커밋되고 다른 주문 항목은 그대로다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            billingPort.release(ReleaseCommand.full(BUYER, ITEM, 5001L, "ORDER-ITEM-5001-CANCEL"));
            lectureSettlementFacade.cancel(5001L);
        });
        assertThat(jdbcTemplate.queryForList(
                "SELECT source_id || ' ' || status FROM billing.settlement ORDER BY source_id", String.class))
                .containsExactly("5001 CANCELLED", "5002 SCHEDULED");
        assertThat(walletRepository.findById(buyer).orElseThrow().getChargeBalanceHp()).isEqualTo(10_000L);
        assertReconciled(buyer);
    }

    @Test
    @DisplayName("반환 → 취소 순서에서 반환이 실패하면 취소는 실행되지 않고, 취소가 실패하면 먼저 한 반환까지 롤백된다")
    void cancelOrReleaseFailureRollsBackBoth() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 20_000L);
        hold(5001L, 10_000L);
        hold(5002L, 10_000L);
        schedule(5001L, SELLER, 10_000L);
        schedule(5002L, SELLER, 10_000L);
        // 취소할 수 없는 상태(지급 실패 FAILED)를 Fixture로 만든다.
        jdbcTemplate.update("UPDATE billing.settlement SET status = 'FAILED', failed_at = now() WHERE source_id = 5002");
        Map<String, Object> before = financialSnapshot();
        List<String> settlementsBefore = settlementRows();

        // 반환 실패: 반환 상세 저장이 실패하면 트랜잭션 전체가 롤백되고 지급 건은 SCHEDULED 그대로다.
        failOn("point_hold_detail", "INSERT", () -> assertThatThrownBy(() -> cancelPurchase(5001L))
                .hasStackTraceContaining("test failure"));
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementRows()).isEqualTo(settlementsBefore);

        // 취소 실패: 반환은 적용됐지만 취소가 SETTLEMENT_INVALID_STATE로 실패하면 반환도 커밋되지 않는다.
        assertBillingError(() -> cancelPurchase(5002L), BillingErrorCode.SETTLEMENT_INVALID_STATE);
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(settlementRows()).isEqualTo(settlementsBefore);
        assertReconciled(buyer);
    }

    // 강의 구매 취소 계약: 같은 트랜잭션에서 보류 반환을 먼저, 지급 건 취소를 다음에 부른다(락 순서 지갑 → 보류 → 지급 건).
    private void cancelPurchase(Long orderItemId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            billingPort.release(ReleaseCommand.full(BUYER, ITEM, orderItemId, "ORDER-ITEM-" + orderItemId + "-CANCEL"));
            lectureSettlementFacade.cancel(orderItemId);
        });
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

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    @Autowired
    HoldFacade holdFacade;

    private HoldResult hold(Long orderItemId, long amountHp) {
        return holdFacade.hold(new HoldCommand(BUYER, ITEM, orderItemId, amountHp));
    }

    private LectureSettlementResult schedule(Long orderItemId, Long sellerUserId, long amountHp) {
        return lectureSettlementFacade.schedule(
                new LectureSettlementCommand(BUYER, sellerUserId, orderItemId, amountHp, PURCHASED_AT));
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    // 충전 완료 건과 같은 금액의 충전 원장을 함께 만들어 충전 원금 합계와 지갑 충전 HP 잔액을 맞춘다.
    private void charge(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + sequence.incrementAndGet(), Timestamp.from(T1),
                Timestamp.from(T1));
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

    // 지갑·충전 원금·보류·상세·배분·원장 상태(지급 건 제외)를 비교할 수 있게 모은다.
    private Map<String, Object> financialSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wallets", jdbcTemplate.queryForList("""
                SELECT id, charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet ORDER BY id"""));
        snapshot.put("charges", jdbcTemplate.queryForList(
                "SELECT id, remaining_principal_hp FROM billing.point_charge ORDER BY id"));
        snapshot.put("holds", jdbcTemplate.queryForList(
                "SELECT id, held_charge_hp, held_revenue_hp, status FROM billing.point_hold ORDER BY id"));
        snapshot.put("details", jdbcTemplate.queryForList("SELECT id FROM billing.point_hold_detail ORDER BY id"));
        snapshot.put("usages", jdbcTemplate.queryForList(
                "SELECT id, remaining_held_hp, consumed_hp, released_hp FROM billing.point_charge_usage ORDER BY id"));
        snapshot.put("ledgers", jdbcTemplate.queryForList(
                "SELECT id, idempotency_key, amount_hp FROM billing.point_ledger ORDER BY id"));
        return snapshot;
    }

    // 지급 건을 "멱등키 받는회원 받는지갑 보류 원천유형 원천ID 지급유형 총액 지급액 상태"로 읽는다.
    private List<String> settlementRows() {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || receiver_user_id || ' ' || receiver_wallet_id || ' ' || point_hold_id || ' '
                    || source_type || ' ' || source_id || ' ' || payout_type || ' ' || gross_amount_hp || ' '
                    || paid_amount_hp || ' ' || status
                FROM billing.settlement ORDER BY id""", String.class);
    }

    private int settlementCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Integer.class);
    }

    private void assertReconciled(Long walletId) {
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).as("원장 대사").isTrue();
    }

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
