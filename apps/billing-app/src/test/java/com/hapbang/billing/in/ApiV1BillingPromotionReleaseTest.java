package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.BudgetReserveCommand;
import com.hapbang.billing.app.HoldCreateUseCase;
import com.hapbang.billing.app.HoldReleaseUseCase;
import com.hapbang.billing.app.HoldVoidResult;
import com.hapbang.billing.app.HoldVoidUseCase;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.PaymentHoldCommand;
import com.hapbang.billing.app.PaymentHoldPromotion;
import com.hapbang.billing.app.PaymentHoldVoidCommand;
import com.hapbang.billing.app.PaymentReleaseCommand;
import com.hapbang.billing.app.PromotionBudgetUseCase;
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;

// 반환과 무효화가 구매자 HP와 같은 쿠폰 예약의 플랫폼 몫을 원자적으로 복구하는지 실제 DB로 확인한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1BillingPromotionReleaseTest {
    private static final long BUYER = 7L;
    private static final BillingReferenceType ORDER = BillingReferenceType.LECTURE_ORDER_ITEM;

    @Autowired MockMvc mockMvc;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerRecordUseCase ledgerRecordUseCase;
    @Autowired PromotionFundingUseCase promotionFundingUseCase;
    @Autowired PromotionBudgetUseCase promotionBudgetUseCase;
    @Autowired HoldCreateUseCase holdCreateUseCase;
    @Autowired HoldReleaseUseCase holdReleaseUseCase;
    @Autowired HoldVoidUseCase holdVoidUseCase;
    @Autowired WalletReconciliationUseCase walletReconciliationUseCase;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("전체 반환은 구매자와 쿠폰별 원래 예약을 복구하고 같은 요청 재생은 처음 금액과 세대를 유지한다")
    void restoresExactReservationsAndReplays() throws Exception {
        Long buyerWalletId = buyer(10_000L);
        budget(42L, 5_000L);
        budget(43L, 3_000L);
        hold(8_000L, new PaymentHoldPromotion(43L, 902L, 0L, 500L),
                new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));

        for (boolean replayed : List.of(false, true)) {
            mockMvc.perform(post("/api/v1/internal/billing/releases")
                    .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(releaseBody("R1", "")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.releasedHp").value(8_000))
                    .andExpect(jsonPath("$.data.remainingHeldHp").value(0))
                    .andExpect(jsonPath("$.data.promotionRestoredHp").value(1_500))
                    .andExpect(jsonPath("$.data.promotionRestorations.length()").value(2))
                    .andExpect(jsonPath("$.data.promotionRestorations[0].couponId").value(42))
                    .andExpect(jsonPath("$.data.promotionRestorations[0].memberCouponId").value(901))
                    .andExpect(jsonPath("$.data.promotionRestorations[0].generation").value(2))
                    .andExpect(jsonPath("$.data.promotionRestorations[1].couponId").value(43))
                    .andExpect(jsonPath("$.data.replayed").value(replayed));
        }
        assertThat(reservation(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
        assertThat(reservation(43L)).containsEntry("remaining_hp", 3_000L).containsEntry("held_hp", 0L);
        assertThat(jdbcTemplate.queryForList("SELECT status, restoration_request_id FROM billing.promotion_hold"))
                .containsOnly(Map.of("status", "RESTORED", "restoration_request_id", "R1"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'PROMOTION_RESTORE'", Long.class)).isEqualTo(4L);
        assertThat(walletRepository.findById(buyerWalletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(10_000L);
        assertThat(walletReconciliationUseCase.reconcile(buyerWalletId).matched()).isTrue();
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
        assertThatThrownBy(() -> release("R2"))
                .isInstanceOfSatisfying(BillingException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BillingErrorCode.HOLD_ALREADY_CLOSED));
    }

    @Test
    @DisplayName("프로모션 반환은 구매자의 충전·수익 HP와 충전 원금을 원래 종류 그대로 복원한다")
    void restoresBuyerPointKindsAndChargePrincipal() {
        Long walletId = buyer(3_000L);
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, 7000, 7000, 7000, 'TOSS_PAYMENTS', 'test-charge', 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class, walletId, Timestamp.from(now), Timestamp.from(now));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, now,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, 7_000L))));
        budget(42L, 5_000L);
        hold(8_000L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));

        ReleaseResult result = release("R1");

        assertThat(result.releasedChargeHp()).isEqualTo(5_000L);
        assertThat(result.releasedRevenueHp()).isEqualTo(3_000L);
        assertThat(result.chargeRestorations()).containsExactly(new ReleaseResult.ChargeRestoration(chargeId, 5_000L));
        Wallet buyer = walletRepository.findById(walletId).orElseThrow();
        assertThat(buyer.getChargeBalanceHp()).isEqualTo(7_000L);
        assertThat(buyer.getRevenueBalanceHp()).isEqualTo(3_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT remaining_principal_hp FROM billing.point_charge WHERE id = ?",
                Long.class, chargeId)).isEqualTo(7_000L);
        assertThat(reservation(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("구매자 몫 0원인 반환도 예산을 복구하며 구매자 원장이나 금액 0 상세를 만들지 않는다")
    void anchorReleaseHasNoZeroLedgerOrDetails() throws Exception {
        Long walletId = buyer(0L);
        budget(42L, 5_000L);
        hold(0L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));

        ReleaseResult result = release("R1");
        ReleaseResult again = release("R1");
        assertThat(result.releasedHp()).isZero();
        assertThat(result.promotionRestoredHp()).isEqualTo(1_000L);
        assertThat(result.holdDetailId()).isNull();
        assertThat(again.promotionRestorations()).isEqualTo(result.promotionRestorations());
        assertThat(again.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?", Long.class, walletId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.point_hold WHERE reference_id = 31", String.class)).isEqualTo("RELEASED");
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
        mockMvc.perform(post("/api/v1/internal/billing/hold-voids")
                .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(voidBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_CLOSED"))
                .andExpect(jsonPath("$.data.promotionRestoredHp").doesNotExist())
                .andExpect(jsonPath("$.data.promotionRestorations").doesNotExist());
    }

    @Test
    @DisplayName("무효화는 0원 앵커도 RELEASED로 기록하며 같은 요청에서 복구 목록을 재생한다")
    void voidAnchorReturnsAndReplaysRestorations() throws Exception {
        buyer(0L);
        budget(42L, 5_000L);
        hold(0L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));

        for (boolean replayed : List.of(false, true)) {
            mockMvc.perform(post("/api/v1/internal/billing/hold-voids")
                    .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(voidBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.outcome").value("RELEASED"))
                    .andExpect(jsonPath("$.data.releasedHp").value(0))
                    .andExpect(jsonPath("$.data.promotionRestoredHp").value(1_000))
                    .andExpect(jsonPath("$.data.promotionRestorations[0].generation").value(2))
                    .andExpect(jsonPath("$.data.replayed").value(replayed));
        }
        assertThat(jdbcTemplate.queryForMap("SELECT outcome, released_hp, promotion_restored_hp FROM billing.hold_void"))
                .containsEntry("outcome", "RELEASED").containsEntry("released_hp", 0L)
                .containsEntry("promotion_restored_hp", 1_000L);
        assertThat(reservation(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("프로모션이 있는 보류의 부분 반환은 양쪽 재원을 변경하지 않고 거절한다")
    void partialReleaseIsRejected() throws Exception {
        Long walletId = buyer(10_000L);
        budget(42L, 5_000L);
        hold(8_000L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));
        Map<String, Object> before = reservation(42L);

        mockMvc.perform(post("/api/v1/internal/billing/releases")
                .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(releaseBody("R1", ",\"amountHp\":1000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_PARTIAL_NOT_SUPPORTED"));

        assertThat(reservation(42L)).isEqualTo(before);
        assertThat(walletRepository.findById(walletId).orElseThrow().getHeldRevenueHp()).isEqualTo(8_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
    }

    @Test
    @DisplayName("예산 복구에 실패하면 먼저 수행한 구매자 반환도 상세 원장과 함께 롤백한다")
    void reservationFailureRollsBackBuyerRelease() {
        Long walletId = buyer(10_000L);
        budget(42L, 5_000L);
        hold(8_000L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));
        jdbcTemplate.update("UPDATE billing.budget_reservation SET remaining_hp = remaining_hp + held_hp, held_hp = 0 WHERE coupon_id = 42");

        assertThatThrownBy(() -> release("R1")).isInstanceOf(BillingException.class);

        Wallet buyer = walletRepository.findById(walletId).orElseThrow();
        assertThat(buyer.getRevenueBalanceHp()).isEqualTo(2_000L);
        assertThat(buyer.getHeldRevenueHp()).isEqualTo(8_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.point_hold WHERE reference_id = 31", String.class)).isEqualTo("HELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE transaction_type IN ('RELEASE','PROMOTION_RESTORE')", Long.class)).isZero();
    }

    @Test
    @DisplayName("반환과 무효화가 경합해도 프로모션 예산은 한 번만 복구된다")
    void releaseAndVoidRaceRestoresOnce() throws Exception {
        Long walletId = buyer(10_000L);
        budget(42L, 5_000L);
        hold(8_000L, new PaymentHoldPromotion(42L, 901L, 2L, 1_000L));
        List<Object> results = concurrent(List.of(() -> release("R1"),
                () -> holdVoidUseCase.voidHold(new PaymentHoldVoidCommand(BUYER, ORDER, 31L))));
        assertThat(results).filteredOn(value -> value instanceof HoldVoidResult).hasSize(1);
        assertThat(results).filteredOn(value -> value instanceof BillingException)
                .allSatisfy(value -> assertThat(((BillingException) value).getErrorCode()).isEqualTo(BillingErrorCode.HOLD_ALREADY_CLOSED));
        assertThat(reservation(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'PROMOTION_RESTORE'", Long.class)).isEqualTo(2L);
        assertThat(walletRepository.findById(walletId).orElseThrow().getRevenueBalanceHp()).isEqualTo(10_000L);
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
    }

    private Long buyer(long revenueHp) {
        Long walletId = walletRepository.save(Wallet.create(BUYER)).getId();
        if (revenueHp > 0) {
            ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:TEST", LedgerTransactionType.PAYOUT,
                    BillingReferenceType.SETTLEMENT, 1L, null, Instant.parse("2026-11-10T00:00:00Z"),
                    List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        }
        return walletId;
    }

    private void budget(long couponId, long amountHp) {
        promotionFundingUseCase.fund(900L, new PromotionFundingCommand("PF-" + couponId, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-" + couponId, null));
        promotionBudgetUseCase.reserve(new BudgetReserveCommand("RESERVE-" + couponId, couponId, 1, amountHp));
    }

    private void hold(long amountHp, PaymentHoldPromotion... promotions) {
        holdCreateUseCase.hold(new PaymentHoldCommand(BUYER, ORDER, 31L, amountHp, List.of(promotions)));
    }

    private ReleaseResult release(String requestId) {
        return holdReleaseUseCase.release(PaymentReleaseCommand.full(BUYER, ORDER, 31L, requestId));
    }

    private Map<String, Object> reservation(long couponId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.budget_reservation WHERE coupon_id = ?", couponId);
    }

    private Wallet promotionWallet() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow();
    }

    private static String releaseBody(String requestId, String extra) {
        return "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"releaseRequestId\":\"%s\"%s}".formatted(requestId, extra);
    }

    private static String voidBody() {
        return "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31}";
    }

    private static List<Object> concurrent(List<Callable<Object>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> executor.submit(() -> { start.await(); return call.call(); })).toList();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                try { results.add(future.get(30, TimeUnit.SECONDS)); }
                catch (ExecutionException error) { results.add(error.getCause()); }
            }
            return results;
        }
    }
}
