package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.BudgetReserveCommand;
import com.hapbang.billing.app.HoldCreateUseCase;
import com.hapbang.billing.app.HoldPayoutUseCase;
import com.hapbang.billing.app.HoldReleaseUseCase;
import com.hapbang.billing.app.PaymentPayoutCommand;
import com.hapbang.billing.app.PaymentReleaseCommand;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.PaymentHoldCommand;
import com.hapbang.billing.app.PaymentHoldPromotion;
import com.hapbang.billing.app.PromotionBudgetUseCase;
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;

// 구매자 보류와 플랫폼 쿠폰 재원을 합쳐 전액 지급하고 재원별 결과·원장·예약 상태를 같은 트랜잭션으로 남긴다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1BillingPromotionPayoutTest {

    private static final long BUYER = 7L;
    private static final long SELLER = 8L;
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerRecordUseCase ledgerRecordUseCase;
    @Autowired PromotionFundingUseCase promotionFundingUseCase;
    @Autowired PromotionBudgetUseCase promotionBudgetUseCase;
    @Autowired HoldCreateUseCase holdCreateUseCase;
    @Autowired HoldPayoutUseCase holdPayoutUseCase;
    @Autowired HoldReleaseUseCase holdReleaseUseCase;
    @Autowired WalletReconciliationUseCase walletReconciliationUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("구매자 보류와 플랫폼 몫을 합쳐 판매자에게 지급하고 지급 건·예약·원장이 재원별 합과 일치한다")
    void combinedPayout() throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, 0L);
        hold(8_000L, 1_000L);

        payout("pay-31", SELLER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(9_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").value(8_000))
                .andExpect(jsonPath("$.data.promotionPaidHp").value(1_000))
                .andExpect(jsonPath("$.data.remainingHeldHp").value(0))
                .andExpect(jsonPath("$.data.replayed").value(false));

        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getHeldRevenueHp()).isZero();
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(9_000L);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM billing.settlement"))
                .containsEntry("gross_amount_hp", 9_000L).containsEntry("paid_amount_hp", 9_000L)
                .containsEntry("buyer_paid_hp", 8_000L).containsEntry("promotion_paid_hp", 1_000L);
        assertThat(reservation()).containsEntry("remaining_hp", 4_000L).containsEntry("held_hp", 0L)
                .containsEntry("paid_hp", 1_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.promotion_hold", String.class))
                .isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.point_hold", String.class)).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(amount_hp) FROM billing.point_ledger
                WHERE operation_key LIKE 'PAYOUT:%:pay-31' AND wallet_id = ?
                  AND point_type = 'REVENUE' AND balance_bucket = 'AVAILABLE'""", Long.class, sellerWalletId))
                .isEqualTo(9_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(amount_hp) FROM billing.point_ledger
                WHERE operation_key LIKE 'PAYOUT:%:pay-31' AND point_type = 'PROMOTION'""", Long.class))
                .isEqualTo(-1_000L);
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    @Test
    @DisplayName("구매자 몫이 0인 앵커도 프로모션 전액을 지급하고 상세·구매자 원장을 만들지 않으며 재요청을 재생한다")
    void anchorPayoutAndReplay() throws Exception {
        Long buyerWalletId = member(BUYER, 0L);
        Long sellerWalletId = member(SELLER, 0L);
        hold(0L, 1_000L);

        payout("pay-31", SELLER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(1_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").value(0))
                .andExpect(jsonPath("$.data.promotionPaidHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        payout("pay-31", SELLER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(1_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").value(0))
                .andExpect(jsonPath("$.data.promotionPaidHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?",
                Long.class, buyerWalletId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Long.class)).isOne();
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(1_000L);
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    @Test
    @DisplayName("같은 지급 요청은 처음 재원별 금액을 재생하고 다른 수령자나 부분 지급 재요청은 거절한다")
    void replayRetainsAmountsAndRejectsChangedRequest() throws Exception {
        member(BUYER, 10_000L);
        member(SELLER, 0L);
        member(9L, 0L);
        hold(8_000L, 1_000L);
        payout("pay-31", SELLER, null).andExpect(status().isOk());

        payout("pay-31", SELLER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(9_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").value(8_000))
                .andExpect(jsonPath("$.data.promotionPaidHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(true));
        payout("pay-31", 9L, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        payout("pay-31", SELLER, 8_000L).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_PARTIAL_NOT_SUPPORTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Long.class)).isOne();
        assertThat(reservation()).containsEntry("paid_hp", 1_000L);
    }

    @Test
    @DisplayName("플랫폼 몫이 있는 보류의 부분 지급은 금액과 무관하게 거절하고 모든 보류를 유지한다")
    void partialPayoutIsRejectedWithoutChanges() throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, 0L);
        hold(8_000L, 1_000L);
        Map<String, Object> before = reservation();

        for (long amount : List.of(1L, 8_000L, 9_000L)) {
            payout("partial-" + amount, SELLER, amount).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROMOTION_PARTIAL_NOT_SUPPORTED"));
        }
        assertThat(reservation()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Long.class)).isZero();
        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getHeldRevenueHp()).isEqualTo(8_000L);
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp()).isZero();
    }

    @Test
    @DisplayName("여러 쿠폰의 플랫폼 몫도 합쳐 한 번 지급하며 각 예약의 보류를 지급됨으로 옮긴다")
    void manyCouponsPayout() throws Exception {
        Long buyerWalletId = member(BUYER, 1_000L);
        Long sellerWalletId = member(SELLER, 0L);
        budget(42L);
        budget(43L);
        holdCreateUseCase.hold(new PaymentHoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 500L,
                List.of(new PaymentHoldPromotion(43L, 902L, 0L, 300L),
                        new PaymentHoldPromotion(42L, 901L, 2L, 200L))));

        payout("pay-31", SELLER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(1_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").value(500))
                .andExpect(jsonPath("$.data.promotionPaidHp").value(500));

        assertThat(jdbcTemplate.queryForList("SELECT paid_hp FROM billing.budget_reservation ORDER BY coupon_id",
                Long.class)).containsExactly(200L, 300L);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM billing.promotion_hold", String.class))
                .containsExactly("PAID", "PAID");
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    @Test
    @DisplayName("판매자 잔액 반영에 실패하면 지급 건·구매자 보류·프로모션 상태·예산·원장이 전부 롤백된다")
    void ledgerFailureRollsBackAllFundingSources() throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, Long.MAX_VALUE);
        hold(8_000L, 1_000L);
        Map<String, Object> before = reservation();
        Long ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger", Long.class);

        payout("pay-31", SELLER, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BALANCE_OVERFLOW"));

        assertThat(reservation()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger", Long.class)).isEqualTo(ledgerCount);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.promotion_hold", String.class)).isEqualTo("HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.point_hold", String.class)).isEqualTo("HELD");
        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getHeldRevenueHp()).isEqualTo(8_000L);
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("플랫폼 몫 없는 지급은 기존 JSON을 유지하며 지급 건의 전액을 구매자 몫으로 기록한다")
    void legacyPayoutRetainsContract() throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, 0L);
        holdCreateUseCase.hold(new PaymentHoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, 8_000L));

        payout("pay-31", SELLER, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidHp").value(8_000))
                .andExpect(jsonPath("$.data.buyerPaidHp").doesNotExist())
                .andExpect(jsonPath("$.data.promotionPaidHp").doesNotExist());
        assertThat(jdbcTemplate.queryForMap("SELECT buyer_paid_hp, promotion_paid_hp FROM billing.settlement"))
                .containsEntry("buyer_paid_hp", 8_000L).containsEntry("promotion_paid_hp", 0L);
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    @Test
    @DisplayName("지급과 반환이 경합해도 하나만 끝나며 두 재원을 함께 지급하거나 함께 복구한다")
    void payoutAndReleaseAreSerialized() throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, 0L);
        hold(8_000L, 1_000L);
        List<Object> outcomes = concurrently(List.of(
                () -> holdPayoutUseCase.payout(PaymentPayoutCommand.full(BUYER,
                        BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "pay-31", SELLER,
                        SettlementSourceType.LECTURE_ORDER_ITEM, 31L)),
                () -> holdReleaseUseCase.release(PaymentReleaseCommand.full(BUYER,
                        BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "release-31"))));

        assertThat(outcomes).filteredOn(value -> value instanceof PayoutResult || value instanceof ReleaseResult)
                .hasSize(1);
        assertThat(outcomes).filteredOn(BillingException.class::isInstance).hasSize(1)
                .allSatisfy(value -> assertThat(((BillingException) value).getErrorCode())
                        .isEqualTo(BillingErrorCode.HOLD_ALREADY_CLOSED));
        boolean paid = outcomes.stream().anyMatch(PayoutResult.class::isInstance);
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp())
                .isEqualTo(paid ? 9_000L : 0L);
        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getRevenueBalanceHp())
                .isEqualTo(paid ? 2_000L : 10_000L);
        assertThat(reservation()).containsEntry("held_hp", 0L).containsEntry("paid_hp", paid ? 1_000L : 0L)
                .containsEntry("remaining_hp", paid ? 4_000L : 5_000L);
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("보류 생성이 미커밋이면 지급은 지갑 락에서 기다린 뒤 생성된 보류를 찾아 전액 지급한다")
    void payoutWaitsForUncommittedHoldCreation(boolean withPromotion) throws Exception {
        Long buyerWalletId = member(BUYER, 10_000L);
        Long sellerWalletId = member(SELLER, 0L);
        if (withPromotion) {
            budget(42L);
        }
        PaymentHoldCommand holdCommand = new PaymentHoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM,
                31L, 8_000L, withPromotion ? List.of(new PaymentHoldPromotion(42L, 901L, 2L, 1_000L)) : List.of());
        CountDownLatch heldButNotCommitted = new CountDownLatch(1);
        CountDownLatch commitHold = new CountDownLatch(1);
        AtomicInteger payoutPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holding = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        holdCreateUseCase.hold(holdCommand);
                        // 보류 INSERT와 지갑 변경은 완료했지만 외부 트랜잭션은 아직 커밋하지 않는다.
                        heldButNotCommitted.countDown();
                        await(commitHold);
                    }));
            assertThat(heldButNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<PayoutResult> paying = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        payoutPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        return holdPayoutUseCase.payout(PaymentPayoutCommand.full(BUYER,
                                BillingReferenceType.LECTURE_ORDER_ITEM, 31L, "pay-31", SELLER,
                                SettlementSourceType.LECTURE_ORDER_ITEM, 31L));
                    }));

            // 지급 세션이 실제 PostgreSQL 락을 기다리는 것을 확인한 뒤에만 보류 생성을 커밋한다.
            awaitPayoutBlockedOnLock(payoutPid, paying);
            assertThat(paying.isDone()).isFalse();
            commitHold.countDown();
            holding.get(30, TimeUnit.SECONDS);

            PayoutResult result = paying.get(30, TimeUnit.SECONDS);
            assertThat(result.paidHp()).isEqualTo(withPromotion ? 9_000L : 8_000L);
            assertThat(result.replayed()).isFalse();
        } finally {
            commitHold.countDown();
            executor.shutdownNow();
        }

        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(2_000L);
        assertThat(walletRepository.findById(sellerWalletId).orElseThrow().getRevenueBalanceHp())
                .isEqualTo(withPromotion ? 9_000L : 8_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.settlement", Long.class)).isOne();
        assertBalanced(buyerWalletId, sellerWalletId);
    }

    private void awaitPayoutBlockedOnLock(AtomicInteger payoutPid, Future<?> paying) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (paying.isDone()) {
                // 락을 잡기 전에 보류를 읽는 회귀에서는 HOLD_NOT_FOUND가 이 자리에서 드러난다.
                paying.get();
                throw new AssertionError("지급이 보류 생성의 커밋을 기다리지 않았습니다.");
            }
            if (payoutPid.get() > 0 && Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)", Boolean.class,
                    payoutPid.get()))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("지급 세션이 지갑 락을 기다리는 상태가 되지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("보류 생성 트랜잭션의 커밋 대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static List<Object> concurrently(List<Callable<Object>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> { start.await(); return call.call(); }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private Long member(long userId, long revenueHp) {
        Long walletId = walletRepository.save(Wallet.create(userId)).getId();
        if (revenueHp > 0) {
            ledgerRecordUseCase.record(new LedgerOperation("TEST-SEED-" + userId, LedgerTransactionType.PAYOUT,
                    BillingReferenceType.SETTLEMENT, userId, null, NOW,
                    List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        }
        return walletId;
    }

    private void budget(long couponId) {
        promotionFundingUseCase.fund(900L, new PromotionFundingCommand("PF-" + couponId, 5_000L,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-" + couponId, null));
        promotionBudgetUseCase.reserve(new BudgetReserveCommand("coupon-" + couponId + "-reserve", couponId, 1, 5_000L));
    }

    private void hold(long buyerHp, long promotionHp) {
        budget(42L);
        holdCreateUseCase.hold(new PaymentHoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, buyerHp,
                List.of(new PaymentHoldPromotion(42L, 901L, 2L, promotionHp))));
    }

    private ResultActions payout(String requestId, long receiverId, Long amountHp) throws Exception {
        return mockMvc.perform(post("/api/v1/internal/billing/payouts")
                .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerUserId":7,"referenceType":"LECTURE_ORDER_ITEM","referenceId":31,
                         "payoutRequestId":"%s","receiverUserId":%d,"sourceType":"LECTURE_ORDER_ITEM",
                         "sourceId":31%s}
                        """.formatted(requestId, receiverId, amountHp == null ? "" : ",\"amountHp\":" + amountHp)));
    }

    private Map<String, Object> reservation() {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.budget_reservation WHERE coupon_id = 42");
    }

    private void assertBalanced(Long buyerWalletId, Long sellerWalletId) {
        for (Long walletId : List.of(buyerWalletId, sellerWalletId,
                walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow().getId())) {
            assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        }
    }
}
