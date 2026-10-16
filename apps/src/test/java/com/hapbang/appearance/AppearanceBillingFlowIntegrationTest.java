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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.appearance.app.AppearanceAutoConfirmUseCase;
import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.HpTaskExecuteUseCase;
import com.hapbang.appearance.config.AppearanceConfig;
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
 * 출연 API가 실제 Billing(shared {@code BillingPort} → Billing 모듈 → billing 스키마)과 통하는지 확인한다 (#289, ISSUE.md APR-36 H4).
 * 출연 API를 그대로 호출하고, 결과를 Billing 지갑 잔액(충전 HP·수익 HP)·보류 남은 HP·HP 작업 상태로 본다.
 * <ul>
 *     <li>출연 모듈 테스트는 Billing을 대역으로 두고 출연 업무 규칙만 확인한다. 돈이 실제로 맞게 움직이는지는 이 테스트가 맡는다.</li>
 *     <li>호스트 HP는 충전 완료 건 + 충전 원장으로 만든다 (Toss 결제를 거치지 않는다). 게스트는 지급받을 지갑만 만든다.</li>
 *     <li>방송이 끝난 뒤 상황은 출연 행의 방송 예정 일시(스냅샷)를 SQL로 옮겨 만든다.</li>
 *     <li>Billing 담당의 {@code AppearanceBillingPortIntegrationTest}(#182)와는 별개 파일이다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AppearanceBillingFlowIntegrationTest {

    private static final String API = "/api/v1/appearances";
    private static final long HOST = 8_001L;
    private static final long GUEST = 8_002L;
    private static final long GUEST2 = 8_003L;
    private static final long FEE = 30_000L;
    private static final long PENALTY = 9_000L;
    private static final Instant FUNDED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;

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
        for (long id : List.of(HOST, GUEST, GUEST2)) {
            appearanceUserFacade.syncJoined(new UserDto(id, "user" + id, Role.INFLUENCER, UserStatus.ACTIVE));
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 요청 (보류 = 요청서)")
    class Request {

        @Test
        @DisplayName("보내면 호스트 충전 HP 30,000이 보류되고, 게스트가 거절하면 그대로 돌려받는다")
        void holdAndReject() throws Exception {
            long hostWallet = fund(HOST, FEE);
            long requestId = sendRequest();

            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isEqualTo(FEE);
            assertThat(chargeBalance(hostWallet)).isZero();

            perform(GUEST, API + "/requests/" + requestId + "/reject", "{\"reason\": \"일정이 맞지 않습니다.\"}")
                    .andExpect(status().isOk());

            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isZero();
            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);
            assertThat(taskStates()).containsExactly("RELEASE DONE");
        }

        @Test
        @DisplayName("잔액이 부족하면 Billing 오류(409 INSUFFICIENT_BALANCE)가 그대로 응답되고 보류는 없다")
        void insufficientBalance() throws Exception {
            fund(HOST, FEE - 1_000);

            perform(HOST, API + "/requests", requestBody())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class))
                    .isZero();
            assertThat(jdbcTemplate.queryForList("SELECT status FROM appearances.requests", String.class))
                    .containsExactly("HOLD_FAILED");
        }

        @Test
        @DisplayName("수락 → 방송 뒤 출연 확인: 게스트 수익 HP +30,000, 보류 0, 지급 작업 DONE")
        void confirm() throws Exception {
            long hostWallet = fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long requestId = sendRequest();
            long appearanceId = accept(requestId);
            broadcastEnded(appearanceId);

            perform(HOST, API + "/" + appearanceId + "/confirm", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.hpPayoutStatus").value("DONE"));

            assertThat(revenueBalance(guestWallet)).isEqualTo(FEE);
            assertThat(chargeBalance(hostWallet)).isZero();
            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isZero();
            assertThat(taskStates()).containsExactly("PAYOUT DONE");
        }

        @Test
        @DisplayName("수락 → 방송 뒤 불참: 호스트 충전 HP 30,000 반환, 게스트 0")
        void absent() throws Exception {
            long hostWallet = fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long requestId = sendRequest();
            long appearanceId = accept(requestId);
            broadcastEnded(appearanceId);

            perform(HOST, API + "/" + appearanceId + "/absent", "{\"reason\": \"연락이 되지 않았습니다.\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ABSENT"))
                    .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));

            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);
            assertThat(revenueBalance(guestWallet)).isZero();
            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isZero();
        }

        @Test
        @DisplayName("수락 → 호스트 일방 취소: 게스트 위약금 9,000(수익 HP), 호스트 21,000 반환")
        void hostCancel() throws Exception {
            long hostWallet = fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long requestId = sendRequest();
            long appearanceId = accept(requestId);

            perform(HOST, API + "/" + appearanceId + "/host-cancel", "{\"reason\": \"방송이 취소됐습니다.\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hpPenaltyStatus").value("DONE"));

            assertThat(revenueBalance(guestWallet)).isEqualTo(PENALTY);
            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE - PENALTY);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isZero();
            assertThat(taskStates()).containsExactly("PENALTY DONE");
        }

        @Test
        @DisplayName("수락 → 게스트 취소 요청 → 호스트 수락(합의 취소): 위약금 없이 호스트 30,000 반환")
        void agreedCancel() throws Exception {
            long hostWallet = fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long requestId = sendRequest();
            long appearanceId = accept(requestId);

            perform(GUEST, API + "/" + appearanceId + "/cancel-requests", "{\"reason\": \"일정이 생겼습니다.\"}")
                    .andExpect(status().isOk());
            perform(HOST, API + "/" + appearanceId + "/cancel-requests/accept", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELED_BY_AGREEMENT"))
                    .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));

            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);
            assertThat(revenueBalance(guestWallet)).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 모집 (보류 = 모집글, 출연별 정산)")
    class Recruit {

        @Test
        @DisplayName("정원 3 (보류 90,000), 2명 성립 → 조기 마감 30,000 반환 → 한 명 확인 30,000 지급, 한 명 불참 30,000 반환 → 보류 0")
        void closeConfirmAbsent() throws Exception {
            long hostWallet = fund(HOST, 3 * FEE);
            long guestWallet = wallet(GUEST);
            long guest2Wallet = wallet(GUEST2);
            long recruitId = postRecruit(3);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(3 * FEE);
            long first = applyAndAccept(recruitId, GUEST);
            long second = applyAndAccept(recruitId, GUEST2);

            perform(HOST, API + "/recruits/" + recruitId + "/close", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(2 * FEE);
            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);

            broadcastEnded(first);
            broadcastEnded(second);
            perform(HOST, API + "/" + first + "/confirm", null).andExpect(status().isOk());
            perform(HOST, API + "/" + second + "/absent", null).andExpect(status().isOk());

            assertThat(revenueBalance(guestWallet)).isEqualTo(FEE);
            assertThat(revenueBalance(guest2Wallet)).isZero();
            assertThat(chargeBalance(hostWallet)).isEqualTo(2 * FEE);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(taskStates()).containsExactlyInAnyOrder("RELEASE DONE", "PAYOUT DONE", "RELEASE DONE");
        }

        @Test
        @DisplayName("정원 3, 1명 성립에서 모집 전체 취소: 게스트 위약금 9,000, 호스트 21,000 + 빈자리 60,000 반환, 보류 0")
        void cancelRecruit() throws Exception {
            long hostWallet = fund(HOST, 3 * FEE);
            long guestWallet = wallet(GUEST);
            long recruitId = postRecruit(3);
            applyAndAccept(recruitId, GUEST);

            perform(HOST, API + "/recruits/" + recruitId + "/cancel", "{\"reason\": \"방송이 취소됐습니다.\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELED"))
                    .andExpect(jsonPath("$.data.hpReleaseStatus").value("DONE"));

            assertThat(revenueBalance(guestWallet)).isEqualTo(PENALTY);
            assertThat(chargeBalance(hostWallet)).isEqualTo(3 * FEE - PENALTY);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(taskStates()).containsExactlyInAnyOrder("PENALTY DONE", "RELEASE DONE");
        }

        @Test
        @DisplayName("마감 전 합의 취소: 반환 없이 보류 유지(자리 다시 열림) → 조기 마감에서 그 자리까지 빈자리 30,000 반환 → 남은 1명 확인 지급 → 보류 0")
        void agreedCancelBeforeClose() throws Exception {
            long hostWallet = fund(HOST, 2 * FEE);
            long guestWallet = wallet(GUEST);
            long guest2Wallet = wallet(GUEST2);
            long recruitId = postRecruit(2);
            long canceled = applyAndAccept(recruitId, GUEST);
            long kept = applyAndAccept(recruitId, GUEST2);

            agreeCancel(canceled);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(2 * FEE);
            assertThat(chargeBalance(hostWallet)).isZero();

            perform(HOST, API + "/recruits/" + recruitId + "/close", null).andExpect(status().isOk());
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(FEE);
            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);

            broadcastEnded(kept);
            perform(HOST, API + "/" + kept + "/confirm", null).andExpect(status().isOk());
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(revenueBalance(guest2Wallet)).isEqualTo(FEE);
            assertThat(revenueBalance(guestWallet)).isZero();
            assertThat(taskStates()).containsExactly("RELEASE DONE", "PAYOUT DONE");
        }

        @Test
        @DisplayName("마감 뒤 합의 취소: 정원이 다 차 마감 반환 없음 → 합의 취소한 1인분 30,000을 출연별로 바로 반환 → 남은 1명 확인 지급 → 보류 0")
        void agreedCancelAfterClose() throws Exception {
            long hostWallet = fund(HOST, 2 * FEE);
            long guestWallet = wallet(GUEST);
            long guest2Wallet = wallet(GUEST2);
            long recruitId = postRecruit(2);
            long canceled = applyAndAccept(recruitId, GUEST);
            long kept = applyAndAccept(recruitId, GUEST2);
            perform(HOST, API + "/recruits/" + recruitId + "/close", null).andExpect(status().isOk());
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(2 * FEE);

            agreeCancel(canceled);
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isEqualTo(FEE);
            assertThat(chargeBalance(hostWallet)).isEqualTo(FEE);

            broadcastEnded(kept);
            perform(HOST, API + "/" + kept + "/confirm", null).andExpect(status().isOk());
            assertThat(heldHp(BillingReferenceType.APPEARANCE_RECRUIT, recruitId)).isZero();
            assertThat(revenueBalance(guest2Wallet)).isEqualTo(FEE);
            assertThat(revenueBalance(guestWallet)).isZero();
            assertThat(taskStates()).containsExactly("RELEASE DONE", "PAYOUT DONE");
        }

        @Test
        @DisplayName("잔액이 부족하면 409 INSUFFICIENT_BALANCE, 모집글은 HOLD_FAILED")
        void insufficientBalance() throws Exception {
            fund(HOST, 3 * FEE - 1_000);

            perform(HOST, API + "/recruits", recruitBody(3))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

            assertThat(jdbcTemplate.queryForList("SELECT status FROM appearances.recruits", String.class))
                    .containsExactly("HOLD_FAILED");
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("재시도·자동 확인·Billing 거절")
    class Robustness {

        @Test
        @DisplayName("지급은 됐는데 완료 기록이 빠져 작업을 다시 실행해도 같은 지급 요청 ID라 게스트에게 한 번만 지급된다")
        void payoutReplay() throws Exception {
            fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long appearanceId = accept(sendRequest());
            broadcastEnded(appearanceId);
            perform(HOST, API + "/" + appearanceId + "/confirm", null).andExpect(status().isOk());
            long taskId = jdbcTemplate.queryForObject("SELECT id FROM appearances.hp_tasks", Long.class);
            jdbcTemplate.update("UPDATE appearances.hp_tasks SET status = 'PENDING', next_attempt_at = now() WHERE id = ?",
                    taskId);

            hpTaskExecuteUseCase.execute(taskId);
            hpTaskExecuteUseCase.execute(taskId);

            assertThat(revenueBalance(guestWallet)).isEqualTo(FEE);
            assertThat(taskStates()).containsExactly("PAYOUT DONE");
        }

        @Test
        @DisplayName("D+7 자동 확인 배치도 실제 Billing으로 지급한다")
        void autoConfirm() throws Exception {
            fund(HOST, FEE);
            long guestWallet = wallet(GUEST);
            long appearanceId = accept(sendRequest());
            jdbcTemplate.update("UPDATE appearances.appearances SET scheduled_at = ? WHERE id = ?",
                    LocalDateTime.now(clock).minusDays(7).minusMinutes(1), appearanceId);

            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isEqualTo(1);

            assertThat(revenueBalance(guestWallet)).isEqualTo(FEE);
            assertThat(taskStates()).containsExactly("PAYOUT DONE");
        }

        @Test
        @DisplayName("게스트 지갑이 없으면 Billing이 지급을 거절하고(WALLET_NOT_FOUND) 작업은 재시도 없이 DEAD, 확인 응답은 성공")
        void payoutRejected() throws Exception {
            fund(HOST, FEE);
            long requestId = sendRequest();
            long appearanceId = accept(requestId);
            broadcastEnded(appearanceId);

            perform(HOST, API + "/" + appearanceId + "/confirm", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.hpPayoutStatus").value("DEAD"));

            assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM appearances.hp_tasks", String.class))
                    .startsWith("WALLET_NOT_FOUND");
            assertThat(heldHp(BillingReferenceType.APPEARANCE_REQUEST, requestId)).isEqualTo(FEE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 도우미
    // ---------------------------------------------------------------------------------------------

    private ResultActions perform(long userId, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(TestLogin.as(userId, Role.INFLUENCER)).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private static long idOf(ResultActions result, String path) throws Exception {
        String response = result.andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, path)).longValue();
    }

    /** 호스트가 게스트에게 출연료 30,000 요청서를 보낸다 (보류 성공). 요청서 ID */
    private long sendRequest() throws Exception {
        return idOf(perform(HOST, API + "/requests", requestBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED")), "$.data.id");
    }

    /** 게스트가 요청서를 수락한다. 성립한 출연 ID */
    private long accept(long requestId) throws Exception {
        long version = jdbcTemplate.queryForObject("SELECT version FROM appearances.requests WHERE id = ?", Long.class,
                requestId);
        return idOf(perform(GUEST, API + "/requests/" + requestId + "/accept", "{\"version\": " + version + "}")
                .andExpect(status().isOk()), "$.data.appearanceId");
    }

    /** 호스트가 1인 30,000으로 모집글을 올린다 (보류 성공). 모집글 ID */
    private long postRecruit(int recruitCount) throws Exception {
        return idOf(perform(HOST, API + "/recruits", recruitBody(recruitCount))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RECRUITING")), "$.data.id");
    }

    /** 게스트가 지원하고 호스트가 수락한다. 성립한 출연 ID */
    private long applyAndAccept(long recruitId, long guestId) throws Exception {
        long applicationId = idOf(perform(guestId, API + "/recruits/" + recruitId + "/applications",
                "{\"message\": \"지원합니다.\"}").andExpect(status().isCreated()), "$.data.id");
        return idOf(perform(HOST, API + "/applications/" + applicationId + "/accept", null)
                .andExpect(status().isOk()), "$.data.appearanceId");
    }

    /** 게스트가 출연 취소를 요청하고 호스트가 수락한다 (합의 취소) */
    private void agreeCancel(long appearanceId) throws Exception {
        perform(GUEST, API + "/" + appearanceId + "/cancel-requests", "{\"reason\": \"일정이 생겼습니다.\"}")
                .andExpect(status().isOk());
        perform(HOST, API + "/" + appearanceId + "/cancel-requests/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED_BY_AGREEMENT"));
    }

    /** 방송이 막 끝났다 (출연의 방송 예정 일시 = 1시간 전, 확인 기간 안) */
    private void broadcastEnded(long appearanceId) {
        jdbcTemplate.update("UPDATE appearances.appearances SET scheduled_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusHours(1), appearanceId);
    }

    private String requestBody() {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {"guestId": %d, "title": "주말 게임 합방 출연 요청", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 신작 게임을 플레이합니다.",
                 "fee": %d, "acceptDeadline": "%s"}
                """.formatted(GUEST, now.plusDays(7).withNano(0), FEE, now.plusDays(2).withNano(0));
    }

    private String recruitBody(int recruitCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        return """
                {"title": "주말 게임 합방 게스트 모집", "hostChannelUrl": "https://www.youtube.com/@host",
                 "scheduledAt": "%s", "expectedMinutes": 60, "content": "같이 플레이할 게스트를 모집합니다.",
                 "feePerPerson": %d, "recruitCount": %d, "recruitDeadline": "%s"}
                """.formatted(now.plusDays(7).withNano(0), FEE, recruitCount, now.plusDays(2).withNano(0));
    }

    /** 지갑을 만들고(또는 있는 지갑) 지갑 ID를 돌려준다 */
    private long wallet(long userId) {
        return walletFacade.getOrCreateWallet(userId).getId();
    }

    /** 충전 완료 건 + 같은 금액의 충전 원장으로 충전 HP 가용 잔액을 만든다 (Toss 결제를 거치지 않는다) */
    private long fund(long userId, long amountHp) {
        long walletId = wallet(userId);
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "appearance-order-" + userId, Timestamp.from(FUNDED_AT),
                Timestamp.from(FUNDED_AT));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, FUNDED_AT,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
        return walletId;
    }

    private long chargeBalance(long walletId) {
        return jdbcTemplate.queryForObject("SELECT charge_balance_hp FROM billing.wallet WHERE id = ?", Long.class,
                walletId);
    }

    private long revenueBalance(long walletId) {
        return jdbcTemplate.queryForObject("SELECT revenue_balance_hp FROM billing.wallet WHERE id = ?", Long.class,
                walletId);
    }

    /** 보류 대상(요청서·모집글)의 남은 보류 HP */
    private long heldHp(BillingReferenceType referenceType, long referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT held_charge_hp + held_revenue_hp FROM billing.point_hold
                WHERE reference_type = ? AND reference_id = ?""", Long.class, referenceType.name(), referenceId);
    }

    /** 출연 HP 작업의 "종류 상태" 목록 (ID 순) */
    private List<String> taskStates() {
        return jdbcTemplate.queryForList("SELECT task_type || ' ' || status FROM appearances.hp_tasks ORDER BY id",
                String.class);
    }
}
