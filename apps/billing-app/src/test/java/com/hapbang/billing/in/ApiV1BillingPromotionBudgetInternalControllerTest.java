package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.BudgetReserveCommand;
import com.hapbang.billing.app.BudgetReserveResult;
import com.hapbang.billing.app.PromotionBudgetFacade;
import com.hapbang.billing.app.PromotionBudgetUseCase;
import com.hapbang.billing.app.PromotionFundingCommand;
import com.hapbang.billing.app.PromotionFundingUseCase;
import com.hapbang.billing.app.WalletReconciliationUseCase;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.fixture.GatewayLogin;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 쿠폰 예산 예약·해제·작업 조회·예약 조회 내부 API의 응답·멱등·거절 무변경·원장·인증을 MockMvc와 실제 PostgreSQL로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiV1BillingPromotionBudgetInternalControllerTest {

    private static final String BASE = "/api/v1/internal/billing/promotion-budgets";
    private static final long ADMIN = 900L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PromotionFundingUseCase promotionFundingUseCase;

    @Autowired
    PromotionBudgetUseCase promotionBudgetUseCase;

    @Autowired
    PromotionBudgetFacade promotionBudgetFacade;

    @Autowired
    WalletReconciliationUseCase walletReconciliationUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------------------------------
    // 예약
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("예약하면 예약 ID(PB-{ID})를 주고, 가용 → 예약으로 옮기는 원장 BUDGET_RESERVE 두 줄과 예약 행을 남긴다")
    void reserve() throws Exception {
        fund(1_000_000L);

        call("/reservations", reserveBody("coupon-42-reserve-v1", 42L, 1, 100_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("쿠폰 예산을 예약했습니다."))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.workId").value("coupon-42-reserve-v1"))
                .andExpect(jsonPath("$.data.reservationId").value("PB-" + reservationRowId(42L)))
                .andExpect(jsonPath("$.data.couponId").value(42))
                .andExpect(jsonPath("$.data.amountHp").value(100_000))
                .andExpect(jsonPath("$.data.replayed").value(false));

        Wallet promotion = promotionWallet();
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(900_000L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(100_000L);
        long rowId = reservationRowId(42L);
        assertThat(reservationRow(rowId)).containsEntry("work_id", "coupon-42-reserve-v1")
                .containsEntry("coupon_id", 42L).containsEntry("policy_version", 1)
                .containsEntry("amount_hp", 100_000L).containsEntry("remaining_hp", 100_000L)
                .containsEntry("held_hp", 0L).containsEntry("paid_hp", 0L).containsEntry("released_hp", 0L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT balance_bucket, amount_hp, operation_key FROM billing.point_ledger
                WHERE transaction_type = 'BUDGET_RESERVE' AND reference_type = 'COUPON_BUDGET' AND reference_id = ?
                ORDER BY id""", rowId)).containsExactly(
                Map.of("balance_bucket", "AVAILABLE", "amount_hp", -100_000L, "operation_key", "BUDGET_RESERVE:" + rowId),
                Map.of("balance_bucket", "RESERVED", "amount_hp", 100_000L, "operation_key", "BUDGET_RESERVE:" + rowId));
        assertThat(walletReconciliationUseCase.reconcile(promotion.getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("같은 workId·같은 내용은 처음 결과(replayed=true)이고, 쿠폰·정책 버전·금액이 다르면 409 IDEMPOTENCY_KEY_CONFLICT다")
    void reserveReplayAndConflict() throws Exception {
        fund(10_000L);
        String request = reserveBody("coupon-42-reserve-v1", 42L, 1, 3_000L);
        call("/reservations", request).andExpect(status().isOk());

        call("/reservations", request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value("PB-" + reservationRowId(42L)))
                .andExpect(jsonPath("$.data.amountHp").value(3_000))
                .andExpect(jsonPath("$.data.replayed").value(true));
        for (String other : List.of(reserveBody("coupon-42-reserve-v1", 43L, 1, 3_000L),
                reserveBody("coupon-42-reserve-v1", 42L, 2, 3_000L),
                reserveBody("coupon-42-reserve-v1", 42L, 1, 3_001L))) {
            assertProblem(call("/reservations", other), 409, "IDEMPOTENCY_KEY_CONFLICT");
        }

        assertThat(count("budget_reservation")).isOne();
        assertThat(ledgerLines("BUDGET_RESERVE")).isEqualTo(2L);
        assertThat(promotionWallet().getReservedPromotionHp()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("가용 HP가 모자라면 409 PROMOTION_BALANCE_INSUFFICIENT이고 아무것도 저장하지 않아 같은 workId로 다시 보낼 수 있다")
    void reserveInsufficient() throws Exception {
        fund(1_000L);
        Map<String, Object> walletBefore = walletRow();

        assertProblem(call("/reservations", reserveBody("coupon-42-reserve-v1", 42L, 1, 1_001L)), 409,
                "PROMOTION_BALANCE_INSUFFICIENT");

        assertThat(walletRow()).isEqualTo(walletBefore);
        assertThat(count("budget_reservation")).isZero();
        assertThat(ledgerLines("BUDGET_RESERVE")).isZero();
        call("/reservations", reserveBody("coupon-42-reserve-v1", 42L, 1, 1_000L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        assertThat(promotionWallet().getPromotionBalanceHp()).isZero();
    }

    @Test
    @DisplayName("같은 쿠폰을 다른 workId로 다시 예약하면 409 BUDGET_RESERVATION_EXISTS이고 아무것도 바뀌지 않는다")
    void reserveSameCouponOtherWork() throws Exception {
        fund(10_000L);
        call("/reservations", reserveBody("coupon-42-reserve-v1", 42L, 1, 3_000L)).andExpect(status().isOk());
        Map<String, Object> walletBefore = walletRow();

        assertProblem(call("/reservations", reserveBody("coupon-42-reserve-v2", 42L, 1, 3_000L)), 409,
                "BUDGET_RESERVATION_EXISTS");

        assertThat(walletRow()).isEqualTo(walletBefore);
        assertThat(count("budget_reservation")).isOne();
        assertThat(ledgerLines("BUDGET_RESERVE")).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("예약·해제 금액이 0 이하면 400 INVALID_POINT_AMOUNT다")
    void invalidAmount(long amountHp) throws Exception {
        fund(10_000L);
        String reservationId = reserve(42L, 3_000L);

        assertProblem(call("/reservations", reserveBody("coupon-43-reserve-v1", 43L, 1, amountHp)), 400,
                "INVALID_POINT_AMOUNT");
        assertProblem(call("/releases", releaseBody("coupon-42-release-v1", reservationId, amountHp, "ADMIN", null)),
                400, "INVALID_POINT_AMOUNT");

        assertThat(count("budget_reservation")).isOne();
        assertThat(count("budget_release")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"workId\":\"coupon:42\",\"couponId\":42,\"policyVersion\":1,\"amountHp\":1}",
            "{\"workId\":\"\",\"couponId\":42,\"policyVersion\":1,\"amountHp\":1}",
            "{\"couponId\":42,\"policyVersion\":1,\"amountHp\":1}",
            "{\"workId\":\"w\",\"couponId\":0,\"policyVersion\":1,\"amountHp\":1}",
            "{\"workId\":\"w\",\"couponId\":42,\"policyVersion\":0,\"amountHp\":1}"})
    @DisplayName("예약 요청의 workId 형식(':' 금지)·쿠폰 ID·정책 버전이 틀리면 400 INVALID_REQUEST다")
    void reserveInvalidRequest(String body) throws Exception {
        fund(10_000L);

        assertProblem(call("/reservations", body), 400, "INVALID_REQUEST");
        assertThat(count("budget_reservation")).isZero();
    }

    @Test
    @DisplayName("다른 쿠폰 예약이 동시에 가용 HP를 넘게 몰려도 예약 합은 가용 HP를 넘지 않고 나머지는 PROMOTION_BALANCE_INSUFFICIENT다")
    void concurrentReservationsCannotExceedAvailable() throws Exception {
        fund(1_000L);
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<BudgetReserveResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                BudgetReserveCommand command = new BudgetReserveCommand("coupon-" + (100 + i) + "-reserve-v1",
                        100L + i, 1, 300L);
                Callable<BudgetReserveResult> call = () -> {
                    start.await();
                    return promotionBudgetUseCase.reserve(command);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            for (Future<BudgetReserveResult> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).filteredOn(BudgetReserveResult.class::isInstance).hasSize(3);
        assertThat(outcomes).filteredOn(BillingException.class::isInstance).hasSize(3)
                .allSatisfy(outcome -> assertThat(((BillingException) outcome).getErrorCode())
                        .isEqualTo(BillingErrorCode.PROMOTION_BALANCE_INSUFFICIENT));
        Wallet promotion = promotionWallet();
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(900L);
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("SELECT sum(remaining_hp) FROM billing.budget_reservation", Long.class))
                .isEqualTo(promotion.getReservedPromotionHp());
        assertThat(walletReconciliationUseCase.reconcile(promotion.getId()).matched()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 해제
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("해제하면 남은 예산에서 빼 가용으로 되돌리고(원장 BUDGET_RELEASE 두 줄) 해제 금액과 남은 예산을 준다")
    void release() throws Exception {
        fund(1_000_000L);
        String reservationId = reserve(42L, 100_000L);
        long rowId = reservationRowId(42L);

        call("/releases", releaseBody("coupon-42-release-unissued-v1", reservationId, 30_000L, "UNISSUED:30", "발급 종료"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 예산을 해제했습니다."))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.workId").value("coupon-42-release-unissued-v1"))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.releasedHp").value(30_000))
                .andExpect(jsonPath("$.data.remainingHp").value(70_000))
                .andExpect(jsonPath("$.data.replayed").value(false));
        call("/releases", releaseBody("coupon-42-release-member-901", reservationId, 18_000L, "MEMBER_COUPON:901", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingHp").value(52_000));

        assertThat(reservationRow(rowId)).containsEntry("remaining_hp", 52_000L).containsEntry("released_hp", 48_000L);
        Wallet promotion = promotionWallet();
        assertThat(promotion.getPromotionBalanceHp()).isEqualTo(948_000L);
        assertThat(promotion.getReservedPromotionHp()).isEqualTo(52_000L);
        Map<String, Object> release = jdbcTemplate.queryForMap(
                "SELECT * FROM billing.budget_release WHERE work_id = 'coupon-42-release-unissued-v1'");
        assertThat(release).containsEntry("budget_reservation_id", rowId).containsEntry("amount_hp", 30_000L)
                .containsEntry("target", "UNISSUED:30").containsEntry("reason", "발급 종료");
        assertThat(jdbcTemplate.queryForList("""
                SELECT balance_bucket, amount_hp, operation_key FROM billing.point_ledger
                WHERE transaction_type = 'BUDGET_RELEASE' AND operation_key = ? ORDER BY id""",
                "BUDGET_RELEASE:" + release.get("id"))).extracting(row -> row.get("balance_bucket") + "/"
                + row.get("amount_hp")).containsExactly("RESERVED/-30000", "AVAILABLE/30000");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(amount_hp) FROM billing.point_ledger
                WHERE transaction_type = 'BUDGET_RELEASE' AND reference_type = 'COUPON_BUDGET' AND reference_id = ?
                  AND balance_bucket = 'AVAILABLE'""", Long.class, rowId)).isEqualTo(48_000L);
        assertThat(walletReconciliationUseCase.reconcile(promotion.getId()).matched()).isTrue();
    }

    @Test
    @DisplayName("같은 해제 workId·같은 내용은 처음 결과, 예약·금액·대상·사유가 다르거나 예약 workId를 쓰면 409 IDEMPOTENCY_KEY_CONFLICT다")
    void releaseReplayAndConflict() throws Exception {
        fund(10_000L);
        String reservationId = reserve(42L, 5_000L);
        String other = reserve(43L, 1_000L);
        String request = releaseBody("coupon-42-release-v1", reservationId, 1_000L, "ADMIN", "사유");
        call("/releases", request).andExpect(status().isOk());

        call("/releases", request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releasedHp").value(1_000))
                .andExpect(jsonPath("$.data.remainingHp").value(4_000))
                .andExpect(jsonPath("$.data.replayed").value(true));
        for (String conflict : List.of(releaseBody("coupon-42-release-v1", other, 1_000L, "ADMIN", "사유"),
                releaseBody("coupon-42-release-v1", reservationId, 999L, "ADMIN", "사유"),
                releaseBody("coupon-42-release-v1", reservationId, 1_000L, "UNISSUED:1", "사유"),
                releaseBody("coupon-42-release-v1", reservationId, 1_000L, "ADMIN", null),
                releaseBody("coupon-42-reserve-v1", reservationId, 1_000L, "ADMIN", null))) {
            assertProblem(call("/releases", conflict), 409, "IDEMPOTENCY_KEY_CONFLICT");
        }
        // 해제에 쓴 workId로 예약할 수도 없다
        assertProblem(call("/reservations", reserveBody("coupon-42-release-v1", 44L, 1, 1L)), 409,
                "IDEMPOTENCY_KEY_CONFLICT");

        assertThat(count("budget_release")).isOne();
        assertThat(ledgerLines("BUDGET_RELEASE")).isEqualTo(2L);
        assertThat(promotionWallet().getReservedPromotionHp()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("남은 예산보다 많이 해제하면 409 BUDGET_RELEASE_EXCEEDS_REMAINING이고 예약·지갑·원장이 그대로다")
    void releaseExceedsRemaining() throws Exception {
        fund(10_000L);
        String reservationId = reserve(42L, 5_000L);
        // 보류 중인 예산은 해제하지 못한다: 보류(이슈 B) 전이라 예약 행의 남은 예산을 보류 중으로 옮겨 둔다
        jdbcTemplate.update("UPDATE billing.budget_reservation SET remaining_hp = 3000, held_hp = 2000 WHERE id = ?",
                reservationRowId(42L));
        Map<String, Object> walletBefore = walletRow();
        Map<String, Object> reservationBefore = reservationRow(reservationRowId(42L));

        assertProblem(call("/releases", releaseBody("coupon-42-release-v1", reservationId, 3_001L, "ADMIN", null)), 409,
                "BUDGET_RELEASE_EXCEEDS_REMAINING");

        assertThat(walletRow()).isEqualTo(walletBefore);
        assertThat(reservationRow(reservationRowId(42L))).isEqualTo(reservationBefore);
        assertThat(count("budget_release")).isZero();
        assertThat(ledgerLines("BUDGET_RELEASE")).isZero();
    }

    @Test
    @DisplayName("없는 예약을 해제하면 404 BUDGET_RESERVATION_NOT_FOUND, 예약 ID 형식·대상이 틀리면 400 INVALID_REQUEST다")
    void releaseNotFoundAndInvalid() throws Exception {
        fund(10_000L);

        assertProblem(call("/releases", releaseBody("coupon-42-release-v1", "PB-999999", 1L, "ADMIN", null)), 404,
                "BUDGET_RESERVATION_NOT_FOUND");
        for (String body : List.of(releaseBody("coupon-42-release-v1", "7", 1L, "ADMIN", null),
                releaseBody("coupon-42-release-v1", "PB-0", 1L, "ADMIN", null),
                releaseBody("coupon:42", "PB-1", 1L, "ADMIN", null),
                "{\"workId\":\"w\",\"reservationId\":\"PB-1\",\"amountHp\":1}",
                releaseBody("w", "PB-1", 1L, " ", null))) {
            assertProblem(call("/releases", body), 400, "INVALID_REQUEST");
        }
        assertThat(count("budget_release")).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 조회
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("작업 조회는 예약·해제 작업이면 DONE과 종류·예약 ID·금액을, 기록이 없으면 200에 NOT_FOUND를 준다")
    void getWork() throws Exception {
        fund(10_000L);
        String reservationId = reserve(42L, 5_000L);
        call("/releases", releaseBody("coupon-42-release-v1", reservationId, 1_200L, "ADMIN", null))
                .andExpect(status().isOk());

        query("/works/coupon-42-reserve-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.workId").value("coupon-42-reserve-v1"))
                .andExpect(jsonPath("$.data.kind").value("RESERVE"))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.amountHp").value(5_000));
        query("/works/coupon-42-release-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("RELEASE"))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.amountHp").value(1_200));
        query("/works/coupon-99-reserve-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workId").value("coupon-99-reserve-v1"))
                .andExpect(jsonPath("$.data.kind").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.reservationId").doesNotExist())
                .andExpect(jsonPath("$.data.amountHp").doesNotExist());
        assertProblem(query("/works/coupon:42"), 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("예약 조회는 총액과 남은·보류·지급·해제 예산을 주고, 없으면 404, 형식이 틀리면 400이다")
    void getReservation() throws Exception {
        fund(10_000L);
        String reservationId = reserve(42L, 5_000L);
        call("/releases", releaseBody("coupon-42-release-v1", reservationId, 1_000L, "ADMIN", null))
                .andExpect(status().isOk());

        query("/reservations/" + reservationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 예산 예약을 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andExpect(jsonPath("$.data.couponId").value(42))
                .andExpect(jsonPath("$.data.amountHp").value(5_000))
                .andExpect(jsonPath("$.data.remainingHp").value(4_000))
                .andExpect(jsonPath("$.data.heldHp").value(0))
                .andExpect(jsonPath("$.data.paidHp").value(0))
                .andExpect(jsonPath("$.data.releasedHp").value(1_000));
        assertProblem(query("/reservations/PB-999999"), 404, "BUDGET_RESERVATION_NOT_FOUND");
        assertProblem(query("/reservations/7"), 400, "INVALID_REQUEST");
    }

    // ---------------------------------------------------------------------------------------------
    // 인증·로그
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("내부 API 키가 없거나 틀리면, 로그인한 회원 헤더가 있어도 본문 없는 401이고 예약하지 않는다")
    void unauthorized() throws Exception {
        fund(10_000L);
        String body = reserveBody("coupon-42-reserve-v1", 42L, 1, 1_000L);

        mockMvc.perform(post(BASE + "/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(post(BASE + "/reservations").header(InternalApiHeaders.API_KEY, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(post(BASE + "/reservations").with(GatewayLogin.as(1L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get(BASE + "/works/coupon-42-reserve-v1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/reservations/PB-1")).andExpect(status().isUnauthorized());

        assertThat(count("budget_reservation")).isZero();
    }

    @Test
    @DisplayName("PromotionBudgetFacade는 내부 호출(ROLE_INTERNAL)만 허용한다: 인증이 없으면 거절, 회원 역할이면 403 대상")
    void facadeRequiresInternalRole() {
        fund(10_000L);
        BudgetReserveCommand command = new BudgetReserveCommand("coupon-42-reserve-v1", 42L, 1, 1_000L);

        assertThatThrownBy(() -> promotionBudgetFacade.reserve(command))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        asRole("ADMIN", () -> assertThatThrownBy(() -> promotionBudgetFacade.reserve(command))
                .isInstanceOf(AccessDeniedException.class));
        asRole("ADMIN", () -> assertThatThrownBy(() -> promotionBudgetFacade.work("coupon-42-reserve-v1"))
                .isInstanceOf(AccessDeniedException.class));
        asRole("INTERNAL", () -> assertThat(promotionBudgetFacade.reserve(command).replayed()).isFalse());
        assertThat(count("budget_reservation")).isOne();
    }

    @Test
    @DisplayName("예약·해제는 요청마다 작업 ID·쿠폰·예약·금액과 결과를 한 줄로 남기고 해제 사유는 남기지 않는다")
    void requestLog(CapturedOutput output) throws Exception {
        fund(1_000L);
        call("/reservations", reserveBody("coupon-42-reserve-v1", 42L, 1, 600L)).andExpect(status().isOk());
        call("/reservations", reserveBody("coupon-43-reserve-v1", 43L, 1, 600L)).andExpect(status().isConflict());
        String reservationId = "PB-" + reservationRowId(42L);
        call("/releases", releaseBody("coupon-42-release-v1", reservationId, 100L, "ADMIN", "비공개 사유"))
                .andExpect(status().isOk());

        assertThat(output.getOut())
                .contains("내부 HP 요청 operation=BUDGET_RESERVE workId=coupon-42-reserve-v1 couponId=42 policyVersion=1 "
                        + "amountHp=600 result=OK replayed=false")
                .contains("내부 HP 요청 operation=BUDGET_RESERVE workId=coupon-43-reserve-v1 couponId=43 policyVersion=1 "
                        + "amountHp=600 result=FAILED code=PROMOTION_BALANCE_INSUFFICIENT")
                .contains("내부 HP 요청 operation=BUDGET_RELEASE workId=coupon-42-release-v1 reservationId=" + reservationId
                        + " amountHp=100 result=OK replayed=false")
                .doesNotContain("비공개 사유");
    }

    @Test
    @DisplayName("Swagger 명세에 쿠폰 예산 내부 API 네 경로가 있다")
    void swagger() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BASE + "/reservations'].post").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/releases'].post").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/works/{workId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/reservations/{reservationId}'].get").exists());
    }

    // ---------------------------------------------------------------------------------------------

    private void fund(long amountHp) {
        promotionFundingUseCase.fund(ADMIN, new PromotionFundingCommand("PF-" + amountHp, amountHp,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-충전", null));
    }

    // 예약하고 예약 ID(PB-{ID})를 돌려준다
    private String reserve(long couponId, long amountHp) throws Exception {
        MvcResult result = call("/reservations", reserveBody("coupon-" + couponId + "-reserve-v1", couponId, 1,
                amountHp)).andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.reservationId");
    }

    private ResultActions call(String path, String body) throws Exception {
        return mockMvc.perform(post(BASE + path).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions query(String path) throws Exception {
        return mockMvc.perform(get(BASE + path).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY));
    }

    private static ResultActions assertProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String reserveBody(String workId, long couponId, int policyVersion, long amountHp) {
        return "{\"workId\":\"%s\",\"couponId\":%d,\"policyVersion\":%d,\"amountHp\":%d}".formatted(workId, couponId,
                policyVersion, amountHp);
    }

    private static String releaseBody(String workId, String reservationId, long amountHp, String target,
            String reason) {
        return "{\"workId\":\"%s\",\"reservationId\":\"%s\",\"amountHp\":%d,\"target\":\"%s\"%s}".formatted(workId,
                reservationId, amountHp, target, reason == null ? "" : ",\"reason\":\"" + reason + "\"");
    }

    private static void asRole(String role, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Wallet promotionWallet() {
        return walletRepository.findByWalletType(WalletType.PROMOTION).orElseThrow();
    }

    private Map<String, Object> walletRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.wallet WHERE wallet_type = 'PROMOTION'");
    }

    private long reservationRowId(long couponId) {
        return jdbcTemplate.queryForObject("SELECT id FROM billing.budget_reservation WHERE coupon_id = ?", Long.class,
                couponId);
    }

    private Map<String, Object> reservationRow(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM billing.budget_reservation WHERE id = ?", id);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing." + table, Long.class);
    }

    private long ledgerLines(String transactionType) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_ledger WHERE transaction_type = ?",
                Long.class, transactionType);
    }
}
