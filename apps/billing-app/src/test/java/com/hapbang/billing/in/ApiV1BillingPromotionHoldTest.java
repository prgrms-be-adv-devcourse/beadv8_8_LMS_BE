package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.BudgetReserveCommand;
import com.hapbang.billing.app.HoldCreateUseCase;
import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.PaymentHoldCommand;
import com.hapbang.billing.app.PaymentHoldPromotion;
import com.hapbang.billing.app.PromotionBudgetUseCase;
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.WalletReconciliation;
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

// 주문 항목 보류에 플랫폼 몫(promotions)을 실은 보류 내부 API를 검증한다: 구매자 보류와 프로모션 보류를 한 트랜잭션으로 만들고,
// 구매자 몫 0은 0원 앵커 보류로, 예약 없음·예산 부족·잔액 부족이면 전부 롤백하며, 같은 참조 대상 재요청은 플랫폼 몫까지 같아야 replay다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1BillingPromotionHoldTest {

    private static final String HOLDS = "/api/v1/internal/billing/holds";
    private static final long BUYER = 7L;
    private static final long ADMIN = 900L;
    private static final Instant T1 = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

    @Autowired
    PromotionBudgetUseCase promotionBudgetUseCase;

    @Autowired
    HoldCreateUseCase holdCreateUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @Test
    @DisplayName("플랫폼 몫이 있으면 구매자 보류와 함께 예약 남은 예산을 프로모션 지갑 보류 칸으로 옮기고 프로모션 보류를 남긴다")
    void holdWithPromotion() throws Exception {
        Long buyerWalletId = buyer(10_000L);
        budget(42L, 5_000L);

        hold(holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 1_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data.referenceType").value("LECTURE_ORDER_ITEM"))
                .andExpect(jsonPath("$.data.referenceId").value(31))
                .andExpect(jsonPath("$.data.heldHp").value(8_000))
                .andExpect(jsonPath("$.data.promotionHeldHp").value(1_000))
                .andExpect(jsonPath("$.data.promotions.length()").value(1))
                .andExpect(jsonPath("$.data.promotions[0].couponId").value(42))
                .andExpect(jsonPath("$.data.promotions[0].memberCouponId").value(901))
                .andExpect(jsonPath("$.data.promotions[0].generation").value(2))
                .andExpect(jsonPath("$.data.promotions[0].amountHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(false));

        Wallet buyer = walletRepository.findById(buyerWalletId).orElseThrow();
        assertThat(buyer.getRevenueBalanceHp()).isEqualTo(2_000L);
        assertThat(buyer.getHeldRevenueHp()).isEqualTo(8_000L);
        Wallet promotion = promotionWallet();
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(4_000L);
        assertThat(promotion.getHeldPromotionHp()).isEqualTo(1_000L);
        Map<String, Object> reservation = reservationRow(42L);
        assertThat(reservation).containsEntry("remaining_hp", 4_000L).containsEntry("held_hp", 1_000L);
        Map<String, Object> promotionHold = jdbcTemplate.queryForMap("SELECT * FROM billing.promotion_hold");
        assertThat(promotionHold).containsEntry("point_hold_id", pointHoldId(31L))
                .containsEntry("budget_reservation_id", reservation.get("id")).containsEntry("coupon_id", 42L)
                .containsEntry("member_coupon_id", 901L).containsEntry("generation", 2L)
                .containsEntry("amount_hp", 1_000L).containsEntry("status", "HELD");
        assertThat(jdbcTemplate.queryForList("""
                SELECT wallet_id, balance_bucket, amount_hp, reference_type, reference_id FROM billing.point_ledger
                WHERE transaction_type = 'PROMOTION_HOLD' AND operation_key = ? ORDER BY id""",
                "PROMOTION_HOLD:" + promotionHold.get("id"))).containsExactly(
                Map.of("wallet_id", promotion.getId(), "balance_bucket", "RESERVED", "amount_hp", -1_000L,
                        "reference_type", "COUPON_BUDGET", "reference_id", reservation.get("id")),
                Map.of("wallet_id", promotion.getId(), "balance_bucket", "HELD", "amount_hp", 1_000L,
                        "reference_type", "COUPON_BUDGET", "reference_id", reservation.get("id")));
        assertThat(walletReconciliationUseCase.reconcile(buyerWalletId).matched()).isTrue();
        WalletReconciliation result = walletReconciliationUseCase.reconcile(promotion.getId());
        assertThat(result.matched()).isTrue();
        assertThat(result.invariant("P3_HELD").rightHp()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("쿠폰이 여럿이면 쿠폰마다 프로모션 보류를 쿠폰 ID 오름차순으로 만든다")
    void holdWithManyPromotions() throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);
        budget(43L, 5_000L);

        hold(holdBody(31L, 1_000L, promotion(43L, 902L, 0L, 300L), promotion(42L, 901L, 1L, 200L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionHeldHp").value(500))
                .andExpect(jsonPath("$.data.promotions[0].couponId").value(42))
                .andExpect(jsonPath("$.data.promotions[1].couponId").value(43));

        assertThat(promotionWallet().getHeldPromotionHp()).isEqualTo(500L);
        assertThat(reservationRow(42L)).containsEntry("held_hp", 200L);
        assertThat(reservationRow(43L)).containsEntry("held_hp", 300L);
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("구매자 몫이 0이고 플랫폼 몫만 있으면 금액 0의 앵커 보류를 만들고 구매자 원장·충전 배분은 만들지 않는다")
    void anchorHold() throws Exception {
        Long buyerWalletId = buyer(0L);
        budget(42L, 5_000L);

        hold(holdBody(31L, 0L, promotion(42L, 901L, 2L, 1_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldHp").value(0))
                .andExpect(jsonPath("$.data.promotionHeldHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(false));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT original_charge_hp, original_revenue_hp, held_charge_hp, held_revenue_hp, status
                FROM billing.point_hold WHERE reference_id = 31""")).containsEntry("original_charge_hp", 0L)
                .containsEntry("original_revenue_hp", 0L).containsEntry("held_charge_hp", 0L)
                .containsEntry("held_revenue_hp", 0L).containsEntry("status", "HELD");
        assertThat(ledgerCount(buyerWalletId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge_usage", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Long.class)).isZero();
        assertThat(promotionWallet().getHeldPromotionHp()).isEqualTo(1_000L);

        // 같은 요청은 앵커 보류와 프로모션 보류를 그대로 돌려준다
        hold(holdBody(31L, 0L, promotion(42L, 901L, 2L, 1_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionHeldHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.promotion_hold", Long.class)).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":0}",
            "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":0,\"promotions\":[]}",
            "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":0,"
                    + "\"promotions\":[{\"couponId\":42,\"memberCouponId\":901,\"generation\":2,\"amountHp\":0}]}",
            "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":-1,"
                    + "\"promotions\":[{\"couponId\":42,\"memberCouponId\":901,\"generation\":2,\"amountHp\":100}]}",
            "{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":100,"
                    + "\"promotions\":[{\"couponId\":42,\"memberCouponId\":901,\"generation\":2,\"amountHp\":-1}]}"})
    @DisplayName("구매자 몫과 플랫폼 몫이 모두 0이거나 음수가 있으면 400 INVALID_POINT_AMOUNT이고 아무것도 만들지 않는다")
    void invalidAmount(String body) throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);

        assertProblem(hold(body), 400, "INVALID_POINT_AMOUNT");

        assertNothingHeld();
    }

    @Test
    @DisplayName("같은 쿠폰이 두 번 있거나 쿠폰 ID가 없으면 400 INVALID_REQUEST다")
    void invalidPromotionStructure() throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);

        assertProblem(hold(holdBody(31L, 100L, promotion(42L, 901L, 2L, 10L), promotion(42L, 902L, 2L, 10L))), 400,
                "INVALID_REQUEST");
        assertProblem(hold(holdBody(31L, 100L, promotion(0L, 901L, 2L, 10L))), 400, "INVALID_REQUEST");
        assertProblem(hold(holdBody(31L, 100L, promotion(42L, 901L, -1L, 10L))), 400, "INVALID_REQUEST");
        assertProblem(hold(holdBody(31L, 100L, "null")), 400, "INVALID_REQUEST");
        assertProblem(hold(holdBody(31L, 100L, "{\"memberCouponId\":901,\"generation\":0,\"amountHp\":10}")),
                400, "INVALID_REQUEST");

        assertNothingHeld();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPEARANCE_REQUEST", "APPEARANCE_RECRUIT"})
    @DisplayName("출연 요청·모집에는 플랫폼 쿠폰 재원을 보류할 수 없다")
    void appearanceCannotUsePromotionBudget(String referenceType) throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);

        assertProblem(hold(holdBody(31L, 1_000L, promotion(42L, 901L, 0L, 100L))
                .replace("LECTURE_ORDER_ITEM", referenceType)), 400, "INVALID_REQUEST");

        assertNothingHeld();
        assertThat(reservationRow(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
    }

    @Test
    @DisplayName("구매자와 플랫폼 몫 합이 long을 넘으면 저장 전에 INVALID_POINT_AMOUNT로 거절한다")
    void totalAmountOverflow() throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);

        assertProblem(hold(holdBody(31L, Long.MAX_VALUE, promotion(42L, 901L, 0L, 1L))),
                400, "INVALID_POINT_AMOUNT");

        assertNothingHeld();
    }

    @Test
    @DisplayName("구매자 보류 기록 뒤 프로모션 원장 저장이 실패하면 구매자·예산·프로모션 보류까지 전부 롤백한다")
    void promotionLedgerFailureRollsBackBuyerHold() throws Exception {
        Long buyerWalletId = buyer(10_000L);
        budget(42L, 5_000L);
        Map<String, Object> buyerBefore = walletRow(buyerWalletId);
        Map<String, Object> promotionBefore = walletRow(promotionWallet().getId());
        Map<String, Object> reservationBefore = reservationRow(42L);
        jdbcTemplate.execute("""
                CREATE FUNCTION billing.test_promotion_hold_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.transaction_type = 'PROMOTION_HOLD' THEN
                        RAISE EXCEPTION 'promotion hold ledger failure';
                    END IF;
                    RETURN NEW;
                END;
                $$""");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_promotion_hold_fail BEFORE INSERT ON billing.point_ledger
                FOR EACH ROW EXECUTE FUNCTION billing.test_promotion_hold_fail()""");
        try {
            assertProblem(hold(holdBody(31L, 8_000L, promotion(42L, 901L, 0L, 1_000L))),
                    500, "INTERNAL_ERROR");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER trg_test_promotion_hold_fail ON billing.point_ledger");
            jdbcTemplate.execute("DROP FUNCTION billing.test_promotion_hold_fail()");
        }

        assertThat(walletRow(buyerWalletId)).isEqualTo(buyerBefore);
        assertThat(walletRow(promotionWallet().getId())).isEqualTo(promotionBefore);
        assertThat(reservationRow(42L)).isEqualTo(reservationBefore);
        assertNothingHeld();
    }

    @Test
    @DisplayName("쿠폰의 예산 예약이 없으면 404 BUDGET_RESERVATION_NOT_FOUND이고 구매자 보류도 만들지 않는다")
    void reservationNotFound() throws Exception {
        Long buyerWalletId = buyer(10_000L);
        budget(42L, 5_000L);
        Map<String, Object> buyerBefore = walletRow(buyerWalletId);

        assertProblem(hold(holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 100L), promotion(99L, 909L, 0L, 100L))),
                404, "BUDGET_RESERVATION_NOT_FOUND");

        assertThat(walletRow(buyerWalletId)).isEqualTo(buyerBefore);
        assertNothingHeld();
        assertThat(reservationRow(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
    }

    @Test
    @DisplayName("플랫폼 몫이 예약 남은 예산보다 많으면 409 PROMOTION_BUDGET_INSUFFICIENT이고 구매자 보류도 만들지 않는다")
    void budgetInsufficient() throws Exception {
        Long buyerWalletId = buyer(10_000L);
        budget(42L, 5_000L);
        Map<String, Object> buyerBefore = walletRow(buyerWalletId);
        Map<String, Object> promotionBefore = walletRow(promotionWallet().getId());

        assertProblem(hold(holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 5_001L))), 409,
                "PROMOTION_BUDGET_INSUFFICIENT");

        assertThat(walletRow(buyerWalletId)).isEqualTo(buyerBefore);
        assertThat(walletRow(promotionWallet().getId())).isEqualTo(promotionBefore);
        assertNothingHeld();
    }

    @Test
    @DisplayName("구매자 잔액이 모자라면 409 INSUFFICIENT_BALANCE이고 예약 남은 예산·프로모션 지갑도 그대로다")
    void buyerInsufficientRollsBackPromotion() throws Exception {
        buyer(1_000L);
        budget(42L, 5_000L);
        Map<String, Object> promotionBefore = walletRow(promotionWallet().getId());

        assertProblem(hold(holdBody(31L, 1_001L, promotion(42L, 901L, 2L, 100L))), 409, "INSUFFICIENT_BALANCE");

        assertThat(walletRow(promotionWallet().getId())).isEqualTo(promotionBefore);
        assertThat(reservationRow(42L)).containsEntry("remaining_hp", 5_000L).containsEntry("held_hp", 0L);
        assertNothingHeld();
    }

    @Test
    @DisplayName("같은 참조 대상 재요청은 구매자 금액과 플랫폼 몫 목록이 같으면 replay, 하나라도 다르면 409 IDEMPOTENCY_KEY_CONFLICT다")
    void replayAndConflict() throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);
        budget(43L, 5_000L);
        String request = holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 1_000L));
        hold(request).andExpect(status().isOk());

        hold(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heldHp").value(8_000))
                .andExpect(jsonPath("$.data.promotionHeldHp").value(1_000))
                .andExpect(jsonPath("$.data.replayed").value(true));
        for (String other : List.of(holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 999L)),
                holdBody(31L, 8_000L, promotion(42L, 902L, 2L, 1_000L)),
                holdBody(31L, 8_000L, promotion(42L, 901L, 3L, 1_000L)),
                holdBody(31L, 8_000L),
                holdBody(31L, 8_000L, promotion(42L, 901L, 2L, 1_000L), promotion(43L, 903L, 0L, 1L)),
                holdBody(31L, 7_999L, promotion(42L, 901L, 2L, 1_000L)))) {
            assertProblem(hold(other), 409, "IDEMPOTENCY_KEY_CONFLICT");
        }
        // 플랫폼 몫 없이 만든 보류에 플랫폼 몫을 붙여 다시 보내도 다른 내용이다
        hold(holdBody(32L, 100L)).andExpect(status().isOk());
        assertProblem(hold(holdBody(32L, 100L, promotion(42L, 901L, 2L, 1L))), 409, "IDEMPOTENCY_KEY_CONFLICT");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.promotion_hold", Long.class)).isOne();
        assertThat(reservationRow(42L)).containsEntry("held_hp", 1_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'PROMOTION_HOLD'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("플랫폼 몫 없는 보류의 요청·응답 JSON과 0 이하 금액 거절은 지금까지와 같다")
    void withoutPromotionsUnchanged() throws Exception {
        buyer(10_000L);

        hold("{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":31,\"amountHp\":8000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.heldHp").value(8_000))
                .andExpect(jsonPath("$.data.promotionHeldHp").doesNotExist())
                .andExpect(jsonPath("$.data.promotions").doesNotExist());
        assertProblem(hold("{\"userId\":7,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":32,\"amountHp\":0}"),
                400, "INVALID_POINT_AMOUNT");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.promotion_hold", Long.class)).isZero();
    }

    @Test
    @DisplayName("다른 주문 항목 보류가 같은 예약의 남은 예산을 넘게 동시에 와도 보류 합은 남은 예산을 넘지 않고 나머지는 전부 롤백된다")
    void concurrentHoldsCannotExceedRemaining() throws Exception {
        budget(42L, 1_000L);
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long userId = 100L + i;
            buyer(userId, 1_000L);
            PaymentHoldCommand command = new PaymentHoldCommand(userId, BillingReferenceType.LECTURE_ORDER_ITEM,
                    200L + i, 500L, List.of(new PaymentHoldPromotion(42L, 900L + i, 0L, 400L)));
            calls.add(() -> holdCreateUseCase.hold(command));
        }

        List<Object> outcomes = runConcurrently(calls);

        assertThat(outcomes).filteredOn(HoldResult.class::isInstance).hasSize(2);
        assertThat(outcomes).filteredOn(BillingException.class::isInstance).hasSize(2)
                .allSatisfy(outcome -> assertThat(((BillingException) outcome).getErrorCode())
                        .isEqualTo(BillingErrorCode.PROMOTION_BUDGET_INSUFFICIENT));
        assertThat(reservationRow(42L)).containsEntry("remaining_hp", 200L).containsEntry("held_hp", 800L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.promotion_hold", Long.class))
                .isEqualTo(2L);
        Wallet promotion = promotionWallet();
        assertThat(promotion.getHeldPromotionHp()).isEqualTo(800L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(200L);
        assertThat(walletReconciliationUseCase.reconcile(promotion.getId()).matched()).isTrue();
        for (int i = 0; i < 4; i++) {
            Long walletId = walletRepository.findByUserId(100L + i).orElseThrow().getId();
            assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
        }
    }

    @Test
    @DisplayName("프로모션 보류 행이 예약의 보류 중과 다르면 대사가 P2_HOLD_RECORDS 불일치로 잡는다")
    void reconciliationCatchesHoldRecordMismatch() throws Exception {
        buyer(10_000L);
        budget(42L, 5_000L);
        hold(holdBody(31L, 1_000L, promotion(42L, 901L, 2L, 1_000L))).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE billing.promotion_hold SET status = 'PAID'");

        WalletReconciliation result = walletReconciliationUseCase.reconcile(promotionWallet().getId());

        assertThat(result.matched()).isFalse();
        assertThat(result.invariants()).filteredOn(check -> !check.matched())
                .extracting(WalletReconciliation.InvariantCheck::name)
                .containsExactly("P2_PAID_RECORDS reservationId=" + reservationRow(42L).get("id"),
                        "P2_HELD_LEDGER reservationId=" + reservationRow(42L).get("id"),
                        "P2_HOLD_RECORDS reservationId=" + reservationRow(42L).get("id"));
    }

    // ---------------------------------------------------------------------------------------------

    private Long buyer(long revenueHp) {
        return buyer(BUYER, revenueHp);
    }

    // 지갑을 만들고 수익 HP 가용 잔액을 넣는다
    private Long buyer(long userId, long revenueHp) {
        Long walletId = walletRepository.save(Wallet.create(userId)).getId();
        if (revenueHp > 0) {
            int id = sequence.incrementAndGet();
            ledgerRecordUseCase.record(new LedgerOperation("PAYOUT:" + id, LedgerTransactionType.PAYOUT,
                    BillingReferenceType.SETTLEMENT, (long) id, null, T1,
                    List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, revenueHp))));
        }
        return walletId;
    }

    // 프로모션 지갑을 충전하고 쿠폰 예산을 예약한다
    private void budget(long couponId, long amountHp) {
        promotionFundingUseCase.fund(ADMIN, new PromotionFundingCommand("PF-" + couponId, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-" + couponId, null));
        promotionBudgetUseCase.reserve(new BudgetReserveCommand("coupon-" + couponId + "-reserve-v1", couponId, 1,
                amountHp));
    }

    private static String promotion(long couponId, long memberCouponId, long generation, long amountHp) {
        return "{\"couponId\":%d,\"memberCouponId\":%d,\"generation\":%d,\"amountHp\":%d}".formatted(couponId,
                memberCouponId, generation, amountHp);
    }

    private static String holdBody(long referenceId, long amountHp, String... promotions) {
        return "{\"userId\":%d,\"referenceType\":\"LECTURE_ORDER_ITEM\",\"referenceId\":%d,\"amountHp\":%d%s}"
                .formatted(BUYER, referenceId, amountHp,
                        promotions.length == 0 ? "" : ",\"promotions\":[" + String.join(",", promotions) + "]");
    }

    private ResultActions hold(String body) throws Exception {
        return mockMvc.perform(post(HOLDS).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private static List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
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

    private Wallet promotionWallet() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow();
    }

    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    private Map<String, Object> reservationRow(long couponId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.budget_reservation WHERE coupon_id = ?", couponId);
    }

    private Long pointHoldId(long referenceId) {
        return jdbcTemplate.queryForObject("SELECT id FROM billing.point_hold WHERE reference_id = ?", Long.class,
                referenceId);
    }

    private long ledgerCount(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?", Long.class,
                walletId);
    }

    private void assertNothingHeld() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.promotion_hold", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM billing.point_ledger WHERE transaction_type IN ('HOLD', 'PROMOTION_HOLD')""",
                Long.class)).isZero();
    }
}
