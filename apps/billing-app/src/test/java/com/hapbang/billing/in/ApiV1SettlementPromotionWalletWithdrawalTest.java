package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.PromotionWithdrawalCommand;
import com.hapbang.billing.app.PromotionWithdrawalResult;
import com.hapbang.billing.app.PromotionWithdrawalUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.fixture.GatewayLogin;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 관리자 프로모션 지갑 회수 API의 응답·멱등·가용 잔액 한도(예약 칸 제외)·검증·권한과 회수 원장·결정 로그를 MockMvc와 실제 PostgreSQL로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiV1SettlementPromotionWalletWithdrawalTest {

    private static final String WITHDRAWALS = "/api/v1/settlements/admin/promotion-wallet/withdrawals";
    private static final long ADMIN = 900L;
    private static final long OTHER_ADMIN = 901L;
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

    @Autowired
    PromotionWithdrawalUseCase promotionWithdrawalUseCase;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    BudgetReservationRepository budgetReservationRepository;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("회수하면 201과 회수 금액·가용 잔액을 주고, 회수 기록 한 건과 원장 PROMOTION_WITHDRAW 한 줄(가용 칸 −)을 남긴다")
    void withdraw() throws Exception {
        fund(10_000L);

        withdraw(ADMIN, body("PW-20261130-01", 3_000L, "결재-2026-130", "남은 재원 회수"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("프로모션 지갑에서 회수했습니다."))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.withdrawalWorkId").value("PW-20261130-01"))
                .andExpect(jsonPath("$.data.amountHp").value(3_000))
                .andExpect(jsonPath("$.data.availableHp").value(7_000))
                .andExpect(jsonPath("$.data.replayed").value(false));

        Long walletId = promotionWallet().getId();
        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(7_000L);
        Map<String, Object> withdrawal = jdbcTemplate.queryForMap(
                "SELECT * FROM billing.promotion_withdrawal WHERE withdrawal_work_id = 'PW-20261130-01'");
        assertThat(withdrawal).containsEntry("wallet_id", walletId).containsEntry("amount_hp", 3_000L)
                .containsEntry("reference_no", "결재-2026-130").containsEntry("memo", "남은 재원 회수")
                .containsEntry("withdrawn_by", ADMIN);
        List<Map<String, Object>> ledger = jdbcTemplate.queryForList("""
                SELECT wallet_id, point_type, balance_bucket, amount_hp, transaction_type, operation_key, idempotency_key
                FROM billing.point_ledger WHERE reference_type = 'PROMOTION_WITHDRAWAL' AND reference_id = ?""",
                withdrawal.get("id"));
        assertThat(ledger).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("wallet_id", walletId).containsEntry("point_type", "PROMOTION")
                .containsEntry("balance_bucket", "AVAILABLE").containsEntry("amount_hp", -3_000L)
                .containsEntry("transaction_type", "PROMOTION_WITHDRAW")
                .containsEntry("operation_key", "PROMOTION_WITHDRAW:" + withdrawal.get("id"))
                .containsEntry("idempotency_key", "PROMOTION_WITHDRAW:" + withdrawal.get("id") + ":1"));
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("같은 회수 작업 ID·같은 내용의 재요청은 다시 회수하지 않고 처음 결과를 준다. 그 뒤 가용 잔액이 모자라도, 다른 관리자가 보내도 같다")
    void replay() throws Exception {
        fund(5_000L);
        String request = body("PW-1", 5_000L, "결재-1", null);
        withdraw(ADMIN, request).andExpect(status().isCreated()).andExpect(jsonPath("$.data.replayed").value(false));

        withdraw(OTHER_ADMIN, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.withdrawalWorkId").value("PW-1"))
                .andExpect(jsonPath("$.data.amountHp").value(5_000))
                .andExpect(jsonPath("$.data.availableHp").value(0))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(promotionWallet().getPromotionBalanceHp()).isZero();
        assertThat(count("promotion_withdrawal")).isOne();
        assertThat(withdrawLedgerLines()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT withdrawn_by FROM billing.promotion_withdrawal", Long.class))
                .isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("같은 회수 작업 ID에 금액·근거 번호·메모가 다르면 409 IDEMPOTENCY_KEY_CONFLICT이고 아무것도 바뀌지 않는다")
    void conflict() throws Exception {
        fund(10_000L);
        withdraw(ADMIN, body("PW-1", 1_000L, "결재-1", "메모")).andExpect(status().isCreated());

        for (String other : List.of(body("PW-1", 1_001L, "결재-1", "메모"), body("PW-1", 1_000L, "결재-2", "메모"),
                body("PW-1", 1_000L, "결재-1", "다른 메모"), body("PW-1", 1_000L, "결재-1", null))) {
            withdraw(ADMIN, other)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        }

        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(9_000L);
        assertThat(count("promotion_withdrawal")).isOne();
        assertThat(withdrawLedgerLines()).isOne();
    }

    @Test
    @DisplayName("예약 칸에 HP가 있어도 가용 칸만큼만 회수한다. 넘으면 409 PROMOTION_BALANCE_INSUFFICIENT이고 아무것도 바뀌지 않는다")
    void onlyUnreservedAvailable() throws Exception {
        fund(1_000L);
        reserve(42L, 600L);
        Long walletId = promotionWallet().getId();
        Map<String, Object> walletBefore = walletRow(walletId);
        long ledgerBefore = ledgerLines(walletId);

        withdraw(ADMIN, body("PW-1", 401L, "결재-1", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_BALANCE_INSUFFICIENT"));

        assertThat(walletRow(walletId)).isEqualTo(walletBefore);
        assertThat(ledgerLines(walletId)).isEqualTo(ledgerBefore);
        assertThat(count("promotion_withdrawal")).isZero();

        // 거절은 저장하지 않으므로 같은 작업 ID로 맞는 금액을 다시 보낼 수 있다
        withdraw(ADMIN, body("PW-1", 400L, "결재-1", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.availableHp").value(0))
                .andExpect(jsonPath("$.data.replayed").value(false));

        Wallet promotion = promotionWallet();
        assertThat(promotion.getPromotionBalanceHp()).isZero();
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(600L);
        assertThat(promotion.getHeldPromotionHp()).isZero();
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("충전한 적이 없는 빈 지갑에서 회수하면 409 PROMOTION_BALANCE_INSUFFICIENT다")
    void emptyWallet() throws Exception {
        withdraw(ADMIN, body("PW-1", 1L, "결재-1", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_BALANCE_INSUFFICIENT"));

        assertNothingWithdrawn();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("회수 금액이 0 이하면 400 INVALID_POINT_AMOUNT이고 기록·원장을 남기지 않는다")
    void invalidAmount(long amountHp) throws Exception {
        fund(1_000L);

        withdraw(ADMIN, body("PW-1", amountHp, "결재-1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINT_AMOUNT"));

        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(1_000L);
        assertNothingWithdrawn();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"withdrawalWorkId\":\"PW:1\",\"amountHp\":1,\"referenceNo\":\"r\"}",
            "{\"withdrawalWorkId\":\"\",\"amountHp\":1,\"referenceNo\":\"r\"}",
            "{\"amountHp\":1,\"referenceNo\":\"r\"}",
            "{\"withdrawalWorkId\":\"PW-1\",\"referenceNo\":\"r\"}",
            "{\"withdrawalWorkId\":\"PW-1\",\"amountHp\":1}",
            "{\"withdrawalWorkId\":\"PW-1\",\"amountHp\":1,\"referenceNo\":\" \"}"})
    @DisplayName("회수 작업 ID 형식(영문·숫자·-·_ 1~200자)이 틀리거나 필수 값이 없으면 400 INVALID_REQUEST다")
    void invalidRequest(String request) throws Exception {
        withdraw(ADMIN, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertNothingWithdrawn();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("관리자가 아니면 회수는 403, 비로그인은 401이고 회수하지 않는다")
    void authorization(Role role) throws Exception {
        fund(1_000L);
        String request = body("PW-1", 1_000L, "결재-1", null);

        // /admin 경로는 보안 설정이 먼저 막으므로 본문 없이 403이다
        mockMvc.perform(post(WITHDRAWALS).with(GatewayLogin.as(2L, role))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mockMvc.perform(post(WITHDRAWALS).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(1_000L);
        assertNothingWithdrawn();
    }

    @Test
    @DisplayName("같은 회수 작업 ID를 동시에 보내도 회수는 한 번이고 나머지는 처음 결과를 받는다")
    void concurrentSameWorkId() throws Exception {
        fund(10_000L);
        PromotionWithdrawalCommand command = new PromotionWithdrawalCommand("PW-1", 3_000L, "결재-1", null);

        List<Object> outcomes = runConcurrently(4, i -> command);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(PromotionWithdrawalResult.class));
        assertThat(outcomes).filteredOn(outcome -> !((PromotionWithdrawalResult) outcome).replayed()).hasSize(1);
        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(7_000L);
        assertThat(count("promotion_withdrawal")).isOne();
        assertThat(withdrawLedgerLines()).isOne();
    }

    @Test
    @DisplayName("다른 회수 작업이 동시에 가용 잔액을 넘게 요청해도 잔액만큼만 회수되고 나머지는 PROMOTION_BALANCE_INSUFFICIENT다")
    void concurrentDifferentWorkIdsCannotOverdraw() throws Exception {
        fund(1_000L);

        List<Object> outcomes = runConcurrently(4,
                i -> new PromotionWithdrawalCommand("PW-" + i, 400L, "결재-" + i, null));

        assertThat(outcomes).filteredOn(PromotionWithdrawalResult.class::isInstance).hasSize(2);
        assertThat(outcomes).filteredOn(BillingException.class::isInstance).hasSize(2)
                .allSatisfy(outcome -> assertThat(((BillingException) outcome).getErrorCode())
                        .isEqualTo(BillingErrorCode.PROMOTION_BALANCE_INSUFFICIENT));
        assertThat(promotionWallet().getPromotionBalanceHp()).isEqualTo(200L);
        assertThat(count("promotion_withdrawal")).isEqualTo(2L);
        assertThat(withdrawLedgerLines()).isEqualTo(2L);
        assertThat(walletReconciliationUseCase.reconcile(promotionWallet().getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("회수 결정 로그에 회수 작업 ID·관리자 회원 ID·금액을 남기고 근거 번호·메모는 남기지 않는다")
    void decisionLog(CapturedOutput output) throws Exception {
        fund(5_000L);
        withdraw(ADMIN, body("PW-LOG-1", 1_234L, "결재-비공개-88", "비공개 메모")).andExpect(status().isCreated());
        withdraw(ADMIN, body("PW-LOG-1", 1_234L, "결재-비공개-88", "비공개 메모")).andExpect(status().isCreated());

        assertThat(output.getOut()).contains("프로모션 지갑에서 회수했다 withdrawalWorkId=PW-LOG-1")
                .contains("adminUserId=" + ADMIN).contains("amountHp=1234")
                .contains("같은 프로모션 회수 작업을 다시 받아 처음 결과를 돌려줬다 withdrawalWorkId=PW-LOG-1")
                .doesNotContain("결재-비공개-88").doesNotContain("비공개 메모");
    }

    @Test
    @DisplayName("Swagger 명세에 프로모션 지갑 회수 경로가 있다")
    void swagger() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + WITHDRAWALS + "'].post").exists());
    }

    // 같은 순간에 회수 UseCase를 여러 번 부르고 각 결과(결과 또는 BillingException)를 모은다
    private List<Object> runConcurrently(int threads, java.util.function.IntFunction<PromotionWithdrawalCommand> commands)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PromotionWithdrawalResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PromotionWithdrawalCommand command = commands.apply(i);
                Callable<PromotionWithdrawalResult> call = () -> {
                    start.await();
                    return promotionWithdrawalUseCase.withdraw(ADMIN, command);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<PromotionWithdrawalResult> future : futures) {
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

    private void fund(long amountHp) {
        promotionFundingUseCase.fund(ADMIN, new PromotionFundingCommand("PF-" + amountHp, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-충전", null));
    }

    // 단계 4(예산 예약 API) 전이라 예약 기록과 예약 원장(가용 → 예약)을 직접 남긴다
    private void reserve(long couponId, long amountHp) {
        Long walletId = promotionWallet().getId();
        BudgetReservation reservation = budgetReservationRepository.saveAndFlush(
                BudgetReservation.reserve("coupon-" + couponId + "-reserve-v1", walletId, couponId, 1, amountHp));
        ledgerRecordUseCase.record(new LedgerOperation("BUDGET_RESERVE:" + reservation.getId(),
                LedgerTransactionType.BUDGET_RESERVE, BillingReferenceType.COUPON_BUDGET, reservation.getId(), null, NOW,
                LedgerEntry.move(walletId, PointType.PROMOTION, BalanceBucket.AVAILABLE, BalanceBucket.RESERVED,
                        amountHp)));
    }

    private ResultActions withdraw(long adminUserId, String request) throws Exception {
        return mockMvc.perform(post(WITHDRAWALS).with(GatewayLogin.as(adminUserId, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }

    private static String body(String withdrawalWorkId, long amountHp, String referenceNo, String memo) {
        return "{\"withdrawalWorkId\":\"" + withdrawalWorkId + "\",\"amountHp\":" + amountHp
                + ",\"referenceNo\":\"" + referenceNo + "\"" + (memo == null ? "" : ",\"memo\":\"" + memo + "\"") + "}";
    }

    private Wallet promotionWallet() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow();
    }

    private Map<String, Object> walletRow(Long walletId) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE id = ?", walletId);
    }

    private long ledgerLines(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE wallet_id = ?", Long.class,
                walletId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing." + table, Long.class);
    }

    private long withdrawLedgerLines() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'PROMOTION_WITHDRAW'", Long.class);
    }

    private void assertNothingWithdrawn() {
        assertThat(count("promotion_withdrawal")).isZero();
        assertThat(withdrawLedgerLines()).isZero();
    }
}
