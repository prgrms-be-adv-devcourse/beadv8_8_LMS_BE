package com.hapbang.appearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.appearance.app.AppearanceRequestHoldUseCase;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.HpTaskExecuteUseCase;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.out.billing.BillingPortClient;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

/**
 * 출연이 {@link BillingPortClient}로 실제 billing을 호출하는지 확인한다 (#182).
 * 출연의 Fake Billing과 {@code appearance.billing.mode} 설정은 #289에서 없어져, 출연은 항상 {@link BillingPortClient}를 쓴다.
 * 출연은 업무 상태를 먼저 커밋하고 트랜잭션 밖에서 billing을 부르는 설계(보류 직접 호출, 반환은 HpTask)이므로, 같은 트랜잭션 원자성은 검증 대상이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AppearanceBillingPortIntegrationTest {

    private static final String BASE = "/api/v1/appearances/requests";
    private static final long HOST = 7_001L;
    private static final long GUEST = 7_002L;
    private static final long FEE = 30_000L;
    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestHoldUseCase appearanceRequestHoldUseCase;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    WalletFacade walletFacade;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(new UserDto(HOST, "host", Role.INFLUENCER, UserStatus.ACTIVE));
        appearanceUserFacade.syncJoined(new UserDto(GUEST, "guest", Role.INFLUENCER, UserStatus.ACTIVE));
    }

    @Test
    void 출연_BillingClient로_BillingPortClient가_주입된다() {
        assertThat(AopUtils.getTargetClass(billingClient)).isEqualTo(BillingPortClient.class);
    }

    @Test
    void 출연_요청을_보내면_실제_billing에_호스트_HP가_보류된다() throws Exception {
        Long hostWalletId = fund(HOST, FEE);

        long requestId = sendRequest();

        assertThat(holds()).containsExactly("HOLD:APPEARANCE_REQUEST:" + requestId + " " + FEE + " HELD");
        assertThat(chargeBalance(hostWalletId)).isZero();
        assertThat(requestStatus(requestId)).isEqualTo("REQUESTED");
    }

    @Test
    void 잔액이_부족하면_billing_오류가_그대로_응답되고_요청서는_HOLD_FAILED가_된다() throws Exception {
        Long hostWalletId = fund(HOST, FEE - 1_000L);

        mockMvc.perform(post(BASE).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(holds()).isEmpty();
        assertThat(chargeBalance(hostWalletId)).isEqualTo(FEE - 1_000L);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM appearances.requests", String.class))
                .containsExactly("HOLD_FAILED");
    }

    @Test
    void 게스트가_거절하면_HpTask가_실제_billing으로_보류를_반환한다() throws Exception {
        Long hostWalletId = fund(HOST, FEE);
        long requestId = sendRequest();

        mockMvc.perform(post(BASE + "/{requestId}/reject", requestId).with(TestLogin.as(GUEST, Role.INFLUENCER))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"일정이 맞지 않습니다.\"}"))
                .andExpect(status().isOk());

        assertThat(holds()).containsExactly("HOLD:APPEARANCE_REQUEST:" + requestId + " 0 RELEASED");
        assertThat(chargeBalance(hostWalletId)).isEqualTo(FEE);
        Long holdId = jdbcTemplate.queryForObject("SELECT id FROM billing.point_hold", Long.class);
        assertThat(jdbcTemplate.queryForList("SELECT idempotency_key FROM billing.point_hold_detail", String.class))
                .containsExactly("RELEASE:" + holdId + ":APPEARANCE_REQUEST-" + requestId + "-RELEASE-1");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM appearances.hp_tasks", String.class))
                .containsExactly("DONE");
    }

    @Test
    void 반환은_성공했는데_작업_완료_기록이_빠져_HpTask를_다시_실행해도_같은_반환_요청_ID라_HP가_두_번_반환되지_않는다() throws Exception {
        Long hostWalletId = fund(HOST, FEE);
        long requestId = sendRequest();
        reject(requestId);
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM appearances.hp_tasks", Long.class);
        // Billing 반환은 커밋됐지만 작업 완료 기록이 실패한 상태(작업이 PENDING으로 남음)를 만든다.
        jdbcTemplate.update("UPDATE appearances.hp_tasks SET status = 'PENDING', next_attempt_at = now() WHERE id = ?",
                taskId);

        hpTaskExecuteUseCase.execute(taskId);
        hpTaskExecuteUseCase.execute(taskId);

        assertThat(chargeBalance(hostWalletId)).isEqualTo(FEE);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold_detail", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM appearances.hp_tasks", String.class))
                .containsExactly("DONE");
    }

    @Test
    void 보류는_성공했는데_REQUESTED_반영이_빠져_HOLD_PENDING이면_같은_요청서로_다시_보류해도_HP가_두_번_묶이지_않는다() throws Exception {
        Long hostWalletId = fund(HOST, 2 * FEE);
        long requestId = sendRequest();
        // 보류는 커밋됐지만 REQUESTED 반영이 실패한 상태를 만든다. 재확인 스케줄러가 없어 지금은 자동 복구되지 않는다.
        jdbcTemplate.update("UPDATE appearances.requests SET status = 'HOLD_PENDING' WHERE id = ?", requestId);

        appearanceRequestHoldUseCase.requestHold(appearanceRequestRepository.findById(requestId).orElseThrow());
        appearanceRequestHoldUseCase.markRequested(requestId);

        assertThat(holds()).containsExactly("HOLD:APPEARANCE_REQUEST:" + requestId + " " + FEE + " HELD");
        assertThat(chargeBalance(hostWalletId)).isEqualTo(FEE);
        assertThat(requestStatus(requestId)).isEqualTo("REQUESTED");
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private void reject(long requestId) throws Exception {
        mockMvc.perform(post(BASE + "/{requestId}/reject", requestId).with(TestLogin.as(GUEST, Role.INFLUENCER))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"일정이 맞지 않습니다.\"}"))
                .andExpect(status().isOk());
    }

    private long sendRequest() throws Exception {
        String response = mockMvc.perform(post(BASE).with(TestLogin.as(HOST, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private String createBody() {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {
                  "guestId": %d,
                  "title": "주말 게임 합방 출연 요청",
                  "hostChannelUrl": "https://www.youtube.com/@host",
                  "scheduledAt": "%s",
                  "expectedMinutes": 60,
                  "content": "같이 신작 게임을 플레이하는 콘텐츠입니다.",
                  "fee": %d,
                  "acceptDeadline": "%s"
                }
                """.formatted(GUEST, now.plusDays(7).withNano(0), FEE, now.plusDays(2).withNano(0));
    }

    // 회원 지갑을 준비하고 충전 완료 건 + 같은 금액의 충전 원장으로 충전 HP 가용 잔액을 만든다(충전 API가 아직 없다).
    private Long fund(Long userId, long amountHp) {
        Long walletId = walletFacade.getOrCreateWallet(userId).getId();
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + userId, Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
        return walletId;
    }

    private List<String> holds() {
        return jdbcTemplate.queryForList("""
                SELECT idempotency_key || ' ' || (held_charge_hp + held_revenue_hp) || ' ' || status
                FROM billing.point_hold ORDER BY id""", String.class);
    }

    private long chargeBalance(Long walletId) {
        return jdbcTemplate.queryForObject("SELECT charge_balance_hp FROM billing.wallet WHERE id = ?", Long.class,
                walletId);
    }

    private String requestStatus(long requestId) {
        return jdbcTemplate.queryForObject("SELECT status FROM appearances.requests WHERE id = ?", String.class,
                requestId);
    }
}
