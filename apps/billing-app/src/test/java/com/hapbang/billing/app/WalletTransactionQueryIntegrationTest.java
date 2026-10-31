package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.billing.app.WalletTransactionResult.PointChange;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

// 내 HP 거래 내역(#295)이 실제 업무 흐름이 남긴 원장을 PostgreSQL GROUP BY로 지갑 + 작업 키마다 묶고, 거래 유형·HP 종류별
// 가용·보류·총액 증감·작업 단위 페이지를 맞게 내는지 검증한다. 원장은 실제 Facade·UseCase로만 기록한다.
@IntegrationTest
// HoldFacade는 내부 호출(ROLE_INTERNAL)만 허용한다(D63, #273)
@WithMockUser(roles = "INTERNAL")
class WalletTransactionQueryIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final BillingReferenceType LECTURE = BillingReferenceType.LECTURE_ORDER_ITEM;
    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final long BUYER = 1L;
    private static final long SELLER = 2L;
    private static final long HOST = 3L;
    private static final long GUEST = 4L;

    @Autowired
    WalletTransactionQueryUseCase walletTransactionQueryUseCase;

    @Autowired
    HoldFacade holdFacade;

    @Autowired
    InternalHoldFacade internalHoldFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    @DisplayName("충전은 충전 HP 가용·총액이 늘어난 CHARGE 한 건이다")
    void charge() {
        Long wallet = wallet(BUYER);
        Long chargeId = charge(wallet, 10_000L, T1);

        WalletTransactionResult tx = only(BUYER);

        assertThat(tx.type()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(tx.type().getDisplayName()).isEqualTo("HP 충전");
        assertThat(tx.referenceType()).isEqualTo(BillingReferenceType.POINT_CHARGE);
        assertThat(tx.referenceId()).isEqualTo(chargeId);
        assertThat(tx.occurredAt()).isEqualTo(T1);
        assertThat(tx.charge()).isEqualTo(new PointChange(10_000L, 0L));
        assertThat(tx.revenue()).isEqualTo(new PointChange(0L, 0L));
        assertAmounts(tx, 10_000L, 0L, 10_000L);
        assertThat(tx.transactionId()).isEqualTo(firstLedgerId("CHARGE:" + chargeId));
    }

    @Test
    @DisplayName("수익·충전 HP를 함께 쓴 결제 보류는 원장 4줄이 거래 1건이고, 종류마다 가용 −·보류 +라 총액 변화는 0이다")
    void mixedHold() {
        Long wallet = wallet(BUYER);
        revenue(wallet, 3_000L);
        charge(wallet, 5_000L, T1);

        HoldResult hold = holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 6_000L));

        assertThat(ledgerLines("HOLD:LECTURE_ORDER_ITEM:11")).isEqualTo(4);
        WalletTransactionResult tx = latest(BUYER);
        assertThat(tx.type()).isEqualTo(WalletTransactionType.PAYMENT_HOLD);
        assertThat(tx.type().getDisplayName()).isEqualTo("결제 보류");
        assertThat(tx.referenceType()).isEqualTo(LECTURE);
        assertThat(tx.referenceId()).isEqualTo(11L);
        assertThat(tx.revenue()).isEqualTo(new PointChange(-3_000L, 3_000L));
        assertThat(tx.charge()).isEqualTo(new PointChange(-3_000L, 3_000L));
        assertThat(tx.revenue().totalChangeHp()).isZero();
        assertThat(tx.charge().totalChangeHp()).isZero();
        assertAmounts(tx, -6_000L, 6_000L, 0L);
        assertThat(hold.revenueHp()).isEqualTo(3_000L);
        assertThat(all(BUYER)).hasSize(3);
    }

    @Test
    @DisplayName("결제 보류 해제는 보류 −·가용 +로 총액 변화 0인 PAYMENT_RELEASE이고, 보류와 별도 거래다")
    void release() {
        Long wallet = wallet(BUYER);
        charge(wallet, 10_000L, T1);
        holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 10_000L));

        holdFacade.release(ReleaseCommand.partial(BUYER, LECTURE, 11L, "R1", 4_000L));

        List<WalletTransactionResult> txs = all(BUYER);
        assertThat(txs).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.PAYMENT_RELEASE, WalletTransactionType.PAYMENT_HOLD, WalletTransactionType.CHARGE);
        WalletTransactionResult release = txs.getFirst();
        assertThat(release.type().getDisplayName()).isEqualTo("결제 보류 해제");
        assertThat(release.charge()).isEqualTo(new PointChange(4_000L, -4_000L));
        assertAmounts(release, 4_000L, -4_000L, 0L);
        assertAmounts(txs.get(1), -10_000L, 10_000L, 0L);
    }

    @Test
    @DisplayName("지급 작업 하나가 두 지갑에 걸쳐도 지갑마다 따로 묶는다: 보낸 쪽은 보류·총액 −(PAYMENT_SETTLED), 받는 쪽은 수익 HP +(EARNING_RECEIVED)")
    void payout_splitByWallet() {
        Long buyer = wallet(BUYER);
        Long seller = wallet(SELLER);
        revenue(buyer, 2_000L);
        charge(buyer, 8_000L, T1);
        HoldResult hold = holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 10_000L));

        holdFacade.payout(PayoutCommand.full(BUYER, LECTURE, 11L, "P1", SELLER, SettlementSourceType.LECTURE_ORDER_ITEM,
                11L));

        String key = "PAYOUT:" + hold.holdId() + ":P1";
        assertThat(walletsOf(key)).containsExactlyInAnyOrder(buyer, seller);
        List<WalletTransactionResult> buyerTxs = all(BUYER);
        assertThat(buyerTxs).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.PAYMENT_SETTLED, WalletTransactionType.PAYMENT_HOLD,
                WalletTransactionType.CHARGE, WalletTransactionType.EARNING_RECEIVED);
        WalletTransactionResult settled = buyerTxs.getFirst();
        assertThat(settled.type().getDisplayName()).isEqualTo("결제 지급 확정");
        assertThat(settled.revenue()).isEqualTo(new PointChange(0L, -2_000L));
        assertThat(settled.charge()).isEqualTo(new PointChange(0L, -8_000L));
        assertAmounts(settled, 0L, -10_000L, -10_000L);

        WalletTransactionResult received = only(SELLER);
        assertThat(received.type()).isEqualTo(WalletTransactionType.EARNING_RECEIVED);
        assertThat(received.type().getDisplayName()).isEqualTo("수익 HP 지급");
        assertThat(received.revenue()).isEqualTo(new PointChange(10_000L, 0L));
        assertThat(received.charge()).isEqualTo(new PointChange(0L, 0L));
        assertAmounts(received, 10_000L, 0L, 10_000L);
        // 같은 작업 키, 같은 첫 원장(보낸 쪽 줄)을 보지 않고 각 지갑의 첫 원장 ID를 쓴다
        assertThat(settled.transactionId()).isLessThan(received.transactionId());
        assertThat(settled.referenceId()).isEqualTo(received.referenceId());
    }

    @Test
    @DisplayName("위약금 정산은 호스트에게 반환(PAYMENT_RELEASE)·위약금 지급 확정(PENALTY_PAID), 게스트에게 위약금 수령(PENALTY_RECEIVED)이다")
    void penalty() {
        Long host = wallet(HOST);
        wallet(GUEST);
        charge(host, 10_000L, T1);
        holdFacade.hold(new HoldCommand(HOST, REQUEST, 7L, 10_000L));

        holdFacade.settlePenalty(new PenaltyCommand(HOST, REQUEST, 7L, "C1", GUEST,
                SettlementSourceType.APPEARANCE_REQUEST, 7L, 7_000L, 3_000L));

        // 분류 근거: 위약금 지급 건(Settlement)이 PENALTY이고, 그 멱등키가 원장 작업 키의 접두사다
        assertThat(jdbcTemplate.queryForList("SELECT payout_type || ' ' || idempotency_key FROM billing.settlement",
                String.class)).singleElement().asString().startsWith("PENALTY PENALTY:");
        List<WalletTransactionResult> hostTxs = all(HOST);
        assertThat(hostTxs).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.PENALTY_PAID, WalletTransactionType.PAYMENT_RELEASE,
                WalletTransactionType.PAYMENT_HOLD, WalletTransactionType.CHARGE);
        assertThat(hostTxs.getFirst().type().getDisplayName()).isEqualTo("위약금 지급 확정");
        assertAmounts(hostTxs.getFirst(), 0L, -3_000L, -3_000L);
        assertAmounts(hostTxs.get(1), 7_000L, -7_000L, 0L);

        WalletTransactionResult guest = only(GUEST);
        assertThat(guest.type()).isEqualTo(WalletTransactionType.PENALTY_RECEIVED);
        assertThat(guest.type().getDisplayName()).isEqualTo("위약금 수령");
        assertThat(guest.revenue()).isEqualTo(new PointChange(3_000L, 0L));
        assertAmounts(guest, 3_000L, 0L, 3_000L);
    }

    @Test
    @DisplayName("충전 HP 환불은 신청 보류(REFUND_HOLD, 총액 0)와 환불 확정(REFUND, 보류·총액 −)이 별도 거래다")
    void refund() {
        Long wallet = wallet(BUYER);
        charge(wallet, 5_000L, T1);

        internalHoldFacade.hold(new InternalHoldCommand(BUYER, BillingReferenceType.POINT_REFUND_REQUEST, 21L, 3_000L));
        internalHoldFacade.capture(CaptureCommand.full(BUYER, BillingReferenceType.POINT_REFUND_REQUEST, 21L, "PG-1"));

        List<WalletTransactionResult> txs = all(BUYER);
        assertThat(txs).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.REFUND, WalletTransactionType.REFUND_HOLD, WalletTransactionType.CHARGE);
        assertThat(txs.getFirst().type().getDisplayName()).isEqualTo("충전 HP 환불");
        assertThat(txs.getFirst().charge()).isEqualTo(new PointChange(0L, -3_000L));
        assertAmounts(txs.getFirst(), 0L, -3_000L, -3_000L);
        assertThat(txs.get(1).type().getDisplayName()).isEqualTo("환불 신청 보류");
        assertAmounts(txs.get(1), -3_000L, 3_000L, 0L);
    }

    @Test
    @DisplayName("수익 HP 환전은 신청 보류(EXCHANGE_HOLD)·환전 확정(EXCHANGE)·보류 해제(EXCHANGE_RELEASE)를 구분한다")
    void exchange() {
        Long wallet = wallet(SELLER);
        revenue(wallet, 5_000L);

        internalHoldFacade.hold(new InternalHoldCommand(SELLER, BillingReferenceType.EXCHANGE, 31L, 2_000L));
        internalHoldFacade.capture(CaptureCommand.full(SELLER, BillingReferenceType.EXCHANGE, 31L, "SENT"));
        internalHoldFacade.hold(new InternalHoldCommand(SELLER, BillingReferenceType.EXCHANGE, 32L, 1_000L));
        internalHoldFacade.release(InternalReleaseCommand.full(SELLER, BillingReferenceType.EXCHANGE, 32L, "CANCEL"));

        List<WalletTransactionResult> txs = all(SELLER);
        assertThat(txs).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.EXCHANGE_RELEASE, WalletTransactionType.EXCHANGE_HOLD,
                WalletTransactionType.EXCHANGE, WalletTransactionType.EXCHANGE_HOLD,
                WalletTransactionType.EARNING_RECEIVED);
        assertThat(txs).extracting(tx -> tx.type().getDisplayName()).containsExactly(
                "환전 보류 해제", "환전 신청 보류", "수익 HP 환전", "환전 신청 보류", "수익 HP 지급");
        assertAmounts(txs.get(0), 1_000L, -1_000L, 0L);
        assertAmounts(txs.get(2), 0L, -2_000L, -2_000L);
        assertThat(txs.get(2).revenue()).isEqualTo(new PointChange(0L, -2_000L));
        assertThat(txs.get(2).referenceType()).isEqualTo(BillingReferenceType.EXCHANGE);
    }

    @Test
    @DisplayName("역분개는 원본을 지우지 않고 반대 부호의 REVERSAL 거래로 남는다")
    void reversal() {
        Long wallet = wallet(BUYER);
        Long chargeId = charge(wallet, 1_000L, T1);
        Long originalId = firstLedgerId("CHARGE:" + chargeId);

        ledgerRecordUseCase.record(new LedgerOperation("REVERSAL:1", LedgerTransactionType.REVERSAL,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T2,
                List.of(new LedgerEntry(wallet, PointType.CHARGE, BalanceBucket.AVAILABLE, -400L, originalId))));

        List<WalletTransactionResult> txs = all(BUYER);
        assertThat(txs).extracting(WalletTransactionResult::type)
                .containsExactly(WalletTransactionType.REVERSAL, WalletTransactionType.CHARGE);
        assertThat(txs.getFirst().type().getDisplayName()).isEqualTo("원장 정정");
        assertThat(txs.getFirst().occurredAt()).isEqualTo(T2);
        assertAmounts(txs.getFirst(), -400L, 0L, -400L);
        assertAmounts(txs.get(1), 1_000L, 0L, 1_000L);
    }

    @Test
    @DisplayName("모든 거래의 종류별 증감 합은 지갑 캐시 잔액과 같다(누락·중복 없음), 총액 = 가용 + 보류")
    void sumsMatchWallet() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        revenue(buyer, 2_500L);
        charge(buyer, 9_000L, T1);
        holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 6_000L));
        holdFacade.release(ReleaseCommand.partial(BUYER, LECTURE, 11L, "R1", 1_000L));
        holdFacade.payout(PayoutCommand.partial(BUYER, LECTURE, 11L, "P1", SELLER,
                SettlementSourceType.LECTURE_ORDER_ITEM, 11L, 2_000L));
        internalHoldFacade.hold(new InternalHoldCommand(BUYER, BillingReferenceType.POINT_REFUND_REQUEST, 21L, 2_000L));

        for (long userId : List.of(BUYER, SELLER)) {
            List<WalletTransactionResult> txs = all(userId);
            Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
            assertThat(txs).allSatisfy(tx -> {
                assertThat(tx.totalChangeHp()).isEqualTo(tx.availableChangeHp() + tx.heldChangeHp());
                assertThat(tx.charge().totalChangeHp())
                        .isEqualTo(tx.charge().availableChangeHp() + tx.charge().heldChangeHp());
                assertThat(tx.availableChangeHp())
                        .isEqualTo(tx.charge().availableChangeHp() + tx.revenue().availableChangeHp());
            });
            assertThat(txs.stream().mapToLong(tx -> tx.charge().availableChangeHp()).sum())
                    .isEqualTo(wallet.getChargeBalanceHp());
            assertThat(txs.stream().mapToLong(tx -> tx.charge().heldChangeHp()).sum())
                    .isEqualTo(wallet.getHeldChargeHp());
            assertThat(txs.stream().mapToLong(tx -> tx.revenue().availableChangeHp()).sum())
                    .isEqualTo(wallet.getRevenueBalanceHp());
            assertThat(txs.stream().mapToLong(tx -> tx.revenue().heldChangeHp()).sum())
                    .isEqualTo(wallet.getHeldRevenueHp());
        }
    }

    @Test
    @DisplayName("페이지는 원장 줄이 아니라 작업 수로 나누고, 첫 원장 ID 내림차순이며 범위 밖 페이지는 빈 목록에 전체 건수를 준다")
    void pagination() {
        Long wallet = wallet(BUYER);
        revenue(wallet, 1_000L);
        charge(wallet, 9_000L, T1);
        // 수익 1,000 + 충전 1,000 → 4줄 작업 3건
        holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 2_000L));
        holdFacade.hold(new HoldCommand(BUYER, LECTURE, 12L, 2_000L));
        holdFacade.hold(new HoldCommand(BUYER, LECTURE, 13L, 2_000L));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?",
                Long.class, wallet)).isEqualTo(2L + 4L + 2L + 2L);

        Page<WalletTransactionResult> first = walletTransactionQueryUseCase.list(BUYER, 0, 2);
        Page<WalletTransactionResult> second = walletTransactionQueryUseCase.list(BUYER, 1, 2);
        Page<WalletTransactionResult> beyond = walletTransactionQueryUseCase.list(BUYER, 5, 2);

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).extracting(WalletTransactionResult::referenceId).containsExactly(13L, 12L);
        assertThat(second.getContent()).extracting(WalletTransactionResult::type).containsExactly(
                WalletTransactionType.PAYMENT_HOLD, WalletTransactionType.CHARGE);
        assertThat(second.getContent().getFirst().referenceId()).isEqualTo(11L);
        // 줄 4개짜리 작업(보류 11)도 한 건으로 센다
        assertAmounts(second.getContent().getFirst(), -2_000L, 2_000L, 0L);
        List<Long> ids = new ArrayList<>();
        all(BUYER).forEach(tx -> ids.add(tx.transactionId()));
        assertThat(ids).isSortedAccordingTo((a, b) -> Long.compare(b, a)).doesNotHaveDuplicates();
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);
        assertThat(walletTransactionQueryUseCase.list(BUYER, 0, 100).getContent()).hasSize(5);
    }

    @Test
    @DisplayName("두 지갑에 걸친 지급 작업도 지갑마다 한 건으로 세고, totalElements는 그 지갑의 작업 수다(원장 줄 수·다른 지갑과 무관)")
    void totalElements_perWallet() {
        Long buyer = wallet(BUYER);
        Long seller = wallet(SELLER);
        revenue(buyer, 2_000L);
        charge(buyer, 8_000L, T1);
        charge(seller, 1_000L, T1);
        HoldResult hold = holdFacade.hold(new HoldCommand(BUYER, LECTURE, 11L, 10_000L));
        holdFacade.payout(PayoutCommand.full(BUYER, LECTURE, 11L, "P1", SELLER, SettlementSourceType.LECTURE_ORDER_ITEM,
                11L));

        // 지급 작업 키 하나가 구매자 2줄(수익·충전 보류 차감) + 판매자 1줄로 두 지갑에 걸친다
        String payoutKey = "PAYOUT:" + hold.holdId() + ":P1";
        assertThat(jdbcTemplate.queryForList("SELECT wallet_id FROM billing.point_ledger WHERE operation_key = ?",
                Long.class, payoutKey)).containsExactlyInAnyOrder(buyer, buyer, seller);
        // 구매자 원장 8줄(수익 1 + 충전 1 + 보류 4 + 지급 2) = 작업 4건, 판매자 원장 2줄 = 작업 2건
        assertThat(ledgerRows(buyer)).isEqualTo(8);
        assertThat(ledgerRows(seller)).isEqualTo(2);

        assertPagedTotals(BUYER, 4);
        assertPagedTotals(SELLER, 2);
        Page<WalletTransactionResult> buyerAll = walletTransactionQueryUseCase.list(BUYER, 0, 100);
        assertThat(buyerAll.getContent()).hasSize(4);
        assertThat(buyerAll.getTotalElements()).isEqualTo(4);
        assertThat(buyerAll.getContent()).filteredOn(tx -> tx.type() == WalletTransactionType.PAYMENT_HOLD)
                .singleElement().satisfies(tx -> assertAmounts(tx, -10_000L, 10_000L, 0L));
        Page<WalletTransactionResult> sellerAll = walletTransactionQueryUseCase.list(SELLER, 0, 100);
        assertThat(sellerAll.getContent()).hasSize(2);
        assertThat(sellerAll.getTotalElements()).isEqualTo(2);
        assertThat(sellerAll.getContent()).extracting(WalletTransactionResult::type)
                .containsExactly(WalletTransactionType.EARNING_RECEIVED, WalletTransactionType.CHARGE);
    }

    // 페이지 크기 1·3으로 끝까지 넘겨도 전체 건수·페이지 수가 같고, 모은 거래가 중복 없이 작업 수와 같다.
    private void assertPagedTotals(long userId, int operations) {
        for (int size : List.of(1, 3)) {
            List<Long> ids = new ArrayList<>();
            int totalPages = (operations + size - 1) / size;
            for (int page = 0; page < totalPages; page++) {
                Page<WalletTransactionResult> result = walletTransactionQueryUseCase.list(userId, page, size);
                assertThat(result.getTotalElements()).isEqualTo(operations);
                assertThat(result.getTotalPages()).isEqualTo(totalPages);
                assertThat(result.getContent()).hasSize(Math.min(size, operations - page * size));
                result.getContent().forEach(tx -> ids.add(tx.transactionId()));
            }
            assertThat(ids).hasSize(operations).doesNotHaveDuplicates();
        }
    }

    private long ledgerRows(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?",
                Long.class, walletId);
    }

    @Test
    @DisplayName("지갑이 없는 회원은 지갑을 만들지 않고 빈 목록이며, 다른 회원의 거래는 보이지 않는다")
    void noWallet_andIsolation() {
        Long buyer = wallet(BUYER);
        charge(buyer, 1_000L, T1);

        Page<WalletTransactionResult> none = walletTransactionQueryUseCase.list(99L, 0, 20);

        assertThat(none.getContent()).isEmpty();
        assertThat(none.getTotalElements()).isZero();
        assertThat(walletRepository.findByUserId(99L)).isEmpty();
        wallet(SELLER);
        assertThat(all(SELLER)).isEmpty();
        assertThat(all(BUYER)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------

    private void assertAmounts(WalletTransactionResult tx, long available, long held, long total) {
        assertThat(tx.availableChangeHp()).isEqualTo(available);
        assertThat(tx.heldChangeHp()).isEqualTo(held);
        assertThat(tx.totalChangeHp()).isEqualTo(total);
    }

    private WalletTransactionResult only(long userId) {
        List<WalletTransactionResult> txs = all(userId);
        assertThat(txs).hasSize(1);
        return txs.getFirst();
    }

    private WalletTransactionResult latest(long userId) {
        return walletTransactionQueryUseCase.list(userId, 0, 1).getContent().getFirst();
    }

    private List<WalletTransactionResult> all(long userId) {
        return walletTransactionQueryUseCase.list(userId, 0, 100).getContent();
    }

    private Long wallet(long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }

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

    private long ledgerLines(String operationKey) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE operation_key = ?",
                Long.class, operationKey);
    }

    private Long firstLedgerId(String operationKey) {
        return jdbcTemplate.queryForObject("SELECT min(id) FROM billing.point_ledger WHERE operation_key = ?",
                Long.class, operationKey);
    }

    private List<Long> walletsOf(String operationKey) {
        return jdbcTemplate.queryForList("SELECT DISTINCT wallet_id FROM billing.point_ledger WHERE operation_key = ?",
                Long.class, operationKey);
    }
}
