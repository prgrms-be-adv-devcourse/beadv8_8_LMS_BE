package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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

import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingResult;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.fixture.GatewayLogin;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 관리자 프로모션 지갑 조회·충전 API의 응답·멱등·검증·권한과 충전 원장·결정 로그를 MockMvc와 실제 PostgreSQL로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiV1SettlementPromotionWalletAdminControllerTest {

    private static final String PATH = "/api/v1/settlements/admin/promotion-wallet";
    private static final String FUNDINGS = PATH + "/fundings";
    private static final long ADMIN = 900L;
    private static final long OTHER_ADMIN = 901L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("충전하면 201과 충전 금액·가용 잔액을 주고, 충전 기록 한 건과 원장 PROMOTION_FUND 한 줄(가용 칸 +)을 남긴다")
    void fund() throws Exception {
        fund(ADMIN, body("PF-20261110-01", 1_000_000L, "결재-2026-118", "11월 프로모션"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("프로모션 지갑을 충전했습니다."))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.fundingWorkId").value("PF-20261110-01"))
                .andExpect(jsonPath("$.data.amountHp").value(1_000_000))
                .andExpect(jsonPath("$.data.availableHp").value(1_000_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        fund(ADMIN, body("PF-20261110-02", 500L, "결재-2026-119", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.availableHp").value(1_000_500));

        Long walletId = promotionWalletId();
        assertThat(promotionBalanceHp()).isEqualTo(1_000_500L);
        Map<String, Object> funding = jdbcTemplate.queryForMap(
                "SELECT * FROM billing.promotion_funding WHERE funding_work_id = 'PF-20261110-01'");
        assertThat(funding).containsEntry("wallet_id", walletId).containsEntry("amount_hp", 1_000_000L)
                .containsEntry("source_type", "ADMIN_GRANT").containsEntry("source_ref", "결재-2026-118")
                .containsEntry("memo", "11월 프로모션").containsEntry("funded_by", ADMIN);
        List<Map<String, Object>> ledger = jdbcTemplate.queryForList("""
                SELECT wallet_id, point_type, balance_bucket, amount_hp, transaction_type, reference_type, reference_id,
                       operation_key, idempotency_key
                FROM billing.point_ledger WHERE reference_type = 'PROMOTION_FUNDING' AND reference_id = ?""",
                funding.get("id"));
        assertThat(ledger).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("wallet_id", walletId).containsEntry("point_type", "PROMOTION")
                .containsEntry("balance_bucket", "AVAILABLE").containsEntry("amount_hp", 1_000_000L)
                .containsEntry("transaction_type", "PROMOTION_FUND")
                .containsEntry("operation_key", "PROMOTION_FUND:" + funding.get("id"))
                .containsEntry("idempotency_key", "PROMOTION_FUND:" + funding.get("id") + ":1"));
        assertThat(walletReconciliationUseCase.reconcile(walletId).matched()).isTrue();
    }

    @Test
    @DisplayName("같은 충전 작업 ID·같은 내용의 재요청은 다시 충전하지 않고 처음 결과(replayed=true)를 준다. 다른 관리자가 보내도 같다")
    void replay() throws Exception {
        String request = body("PF-1", 3_000L, "결재-1", "메모");
        fund(ADMIN, request).andExpect(status().isCreated()).andExpect(jsonPath("$.data.replayed").value(false));

        fund(OTHER_ADMIN, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fundingWorkId").value("PF-1"))
                .andExpect(jsonPath("$.data.amountHp").value(3_000))
                .andExpect(jsonPath("$.data.availableHp").value(3_000))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(promotionBalanceHp()).isEqualTo(3_000L);
        assertThat(count("promotion_funding")).isOne();
        assertThat(fundLedgerLines()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT funded_by FROM billing.promotion_funding", Long.class))
                .isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("같은 충전 작업 ID에 금액·근거 번호·메모가 다르면 409 IDEMPOTENCY_KEY_CONFLICT이고 아무것도 바뀌지 않는다")
    void conflict() throws Exception {
        fund(ADMIN, body("PF-1", 3_000L, "결재-1", "메모")).andExpect(status().isCreated());

        for (String other : List.of(body("PF-1", 3_001L, "결재-1", "메모"), body("PF-1", 3_000L, "결재-2", "메모"),
                body("PF-1", 3_000L, "결재-1", "다른 메모"), body("PF-1", 3_000L, "결재-1", null))) {
            fund(ADMIN, other)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        }

        assertThat(promotionBalanceHp()).isEqualTo(3_000L);
        assertThat(count("promotion_funding")).isOne();
        assertThat(fundLedgerLines()).isOne();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("충전 금액이 0 이하면 400 INVALID_POINT_AMOUNT이고 기록·원장을 남기지 않는다")
    void invalidAmount(long amountHp) throws Exception {
        fund(ADMIN, body("PF-1", amountHp, "결재-1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINT_AMOUNT"));

        assertNothingFunded();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fundingWorkId\":\"PF:1\",\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"\",\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"충전-1\",\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"r\"}",
            "{\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"PF-1\",\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"PF-1\",\"amountHp\":1,\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"PF-1\",\"amountHp\":1,\"sourceType\":\"UNDEFINED\",\"sourceRef\":\"r\"}",
            "{\"fundingWorkId\":\"PF-1\",\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\"}",
            "{\"fundingWorkId\":\"PF-1\",\"amountHp\":1,\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\" \"}"})
    @DisplayName("충전 작업 ID 형식(영문·숫자·-·_ 1~200자)이 틀리거나 필수 값이 없으면 400 INVALID_REQUEST다")
    void invalidRequest(String request) throws Exception {
        fund(ADMIN, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertNothingFunded();
    }

    @Test
    @DisplayName("충전 작업 ID는 200자까지 받고 201자는 400 INVALID_REQUEST다")
    void workIdLength() throws Exception {
        fund(ADMIN, body("x".repeat(201), 1L, "결재-1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        fund(ADMIN, body("x".repeat(200), 1L, "결재-1", null)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("같은 충전 작업 ID를 동시에 보내도 충전은 한 번이고 나머지는 처음 결과를 받는다")
    void concurrentSameWorkId() throws Exception {
        int threads = 4;
        PromotionFundingCommand command = new PromotionFundingCommand("PF-1", 7_000L,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-1", null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PromotionFundingResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<PromotionFundingResult> call = () -> {
                    start.await();
                    return promotionFundingUseCase.fund(ADMIN, command);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<PromotionFundingResult> results = new ArrayList<>();
            for (Future<PromotionFundingResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).allSatisfy(result -> assertThat(result.amountHp()).isEqualTo(7_000L));
        } finally {
            executor.shutdownNow();
        }
        assertThat(promotionBalanceHp()).isEqualTo(7_000L);
        assertThat(count("promotion_funding")).isOne();
        assertThat(fundLedgerLines()).isOne();
    }

    @Test
    @DisplayName("충전 결정 로그에 충전 작업 ID·관리자 회원 ID·금액을 남기고 근거 번호·메모는 남기지 않는다")
    void decisionLog(CapturedOutput output) throws Exception {
        fund(ADMIN, body("PF-LOG-1", 1_234L, "결재-비공개-77", "비공개 메모")).andExpect(status().isCreated());
        fund(ADMIN, body("PF-LOG-1", 1_234L, "결재-비공개-77", "비공개 메모")).andExpect(status().isCreated());

        assertThat(output.getOut()).contains("프로모션 지갑을 충전했다 fundingWorkId=PF-LOG-1")
                .contains("adminUserId=" + ADMIN).contains("amountHp=1234")
                .contains("같은 프로모션 충전 작업을 다시 받아 처음 결과를 돌려줬다 fundingWorkId=PF-LOG-1")
                .doesNotContain("결재-비공개-77").doesNotContain("비공개 메모");
    }

    @Test
    @DisplayName("조회하면 프로모션 지갑의 가용·예약·보류 칸별 잔액을 준다")
    void getPromotionWallet() throws Exception {
        Long walletId = promotionWalletId();
        jdbcTemplate.update("""
                UPDATE billing.wallet SET promotion_balance_hp = 700, reserved_promotion_hp = 200, held_promotion_hp = 100
                WHERE id = ?""", walletId);

        mockMvc.perform(get(PATH).with(GatewayLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("프로모션 지갑을 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data.walletId").value(walletId))
                .andExpect(jsonPath("$.data.availableHp").value(700))
                .andExpect(jsonPath("$.data.reservedHp").value(200))
                .andExpect(jsonPath("$.data.heldHp").value(100));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("관리자가 아니면 조회·충전 모두 403, 비로그인은 401이고 충전하지 않는다")
    void authorization(Role role) throws Exception {
        String request = body("PF-1", 1_000L, "결재-1", null);
        // /admin 경로는 보안 설정이 먼저 막으므로 본문 없이 403이다(플랫폼 지갑 관리자 API와 같다)
        mockMvc.perform(get(PATH).with(GatewayLogin.as(2L, role))).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        fund(2L, role, request).andExpect(status().isForbidden());
        mockMvc.perform(post(FUNDINGS).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        assertNothingFunded();
    }

    @Test
    @DisplayName("Swagger 명세에 프로모션 지갑 조회·충전 경로가 있다")
    void swagger() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PATH + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + FUNDINGS + "'].post").exists());
    }

    private ResultActions fund(long adminUserId, String request) throws Exception {
        return fund(adminUserId, Role.ADMIN, request);
    }

    private ResultActions fund(long userId, Role role, String request) throws Exception {
        return mockMvc.perform(post(FUNDINGS).with(GatewayLogin.as(userId, role))
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }

    private static String body(String fundingWorkId, long amountHp, String sourceRef, String memo) {
        return "{\"fundingWorkId\":\"" + fundingWorkId + "\",\"amountHp\":" + amountHp
                + ",\"sourceType\":\"ADMIN_GRANT\",\"sourceRef\":\"" + sourceRef + "\""
                + (memo == null ? "" : ",\"memo\":\"" + memo + "\"") + "}";
    }

    private Long promotionWalletId() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow().getId();
    }

    private long promotionBalanceHp() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow().getPromotionBalanceHp();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing." + table, Long.class);
    }

    private long fundLedgerLines() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_ledger WHERE transaction_type = 'PROMOTION_FUND'", Long.class);
    }

    private void assertNothingFunded() {
        assertThat(promotionBalanceHp()).isZero();
        assertThat(count("promotion_funding")).isZero();
        assertThat(fundLedgerLines()).isZero();
    }
}
