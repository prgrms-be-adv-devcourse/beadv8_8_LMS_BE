package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.billing.app.ExchangeCommand;
import com.hapbang.billing.app.ExchangeRequestUseCase;
import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 관리자 환전 API(#294)의 권한(비로그인 401, 비관리자 403)·RsData 응답·계좌번호 마스킹·오류 코드를 MockMvc로 검증한다.
// 이 클래스만 쓰는 컨텍스트라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게).
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiV1SettlementExchangeAdminControllerTest {

    private static final String PATH = "/api/v1/settlements/admin/exchanges";
    private static final long INFLUENCER = 2L;
    private static final long ADMIN = 900L;
    private static final String ACCOUNT = "12345678901234";

    @DynamicPropertySource
    static void cipherProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ChargeFixture.ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> ChargeFixture.HMAC_KEY);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    ExchangeRequestUseCase exchangeRequestUseCase;

    @Autowired
    BillingUserSyncUseCase billingUserSyncUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long exchangeId;

    @BeforeEach
    void setUp() {
        Long walletId = walletRepository.save(Wallet.create(INFLUENCER)).getId();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:1", LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, 1L, null, Instant.parse("2026-10-01T00:00:00Z"),
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, 20_000L))));
        exchangeId = exchangeRequestUseCase.create(INFLUENCER, "key-1",
                new ExchangeCommand(10_000L, "004", ACCOUNT, "홍길동"));
    }

    @Test
    @DisplayName("관리자는 목록·상세를 조회하고 승인할 수 있다. 계좌번호는 마스킹하고 암호문은 응답에 없다")
    void listGetApprove() throws Exception {
        billingUserSyncUseCase.apply(new UserDto(INFLUENCER, "신청자", Role.INFLUENCER, UserStatus.ACTIVE, 0L));

        String list = mockMvc.perform(get(PATH).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 신청 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.data.content[0].userId").value(INFLUENCER))
                .andExpect(jsonPath("$.data.content[0].requestedAmount").value(10_000))
                .andExpect(jsonPath("$.data.content[0].feeAmount").value(1_000))
                .andExpect(jsonPath("$.data.content[0].expectedPayoutAmount").value(9_000))
                .andExpect(jsonPath("$.data.content[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.content[0].settlementRoundDate").isString())
                .andExpect(jsonPath("$.data.content[0].account.bankCode").value("004"))
                .andExpect(jsonPath("$.data.content[0].account.accountNumberMasked").value("**********1234"))
                .andExpect(jsonPath("$.data.content[0].applicant.approvable").value(true))
                .andReturn().getResponse().getContentAsString();
        String approved = mockMvc.perform(post(PATH + "/{id}/approve", exchangeId).with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 신청을 승인했습니다."))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedBy").value(ADMIN))
                .andExpect(jsonPath("$.data.approvedAt").isString())
                .andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(list + approved + detail).doesNotContain(ACCOUNT).doesNotContain("v1:")
                .doesNotContain("accountNumberEncrypted");
        mockMvc.perform(get(PATH).param("status", "APPROVED").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(exchangeId));
    }

    @Test
    @DisplayName("복제본 없는 신청자 승인은 409 EXCHANGE_APPLICANT_NOT_SYNCED, 거절은 사유를 받아 200이고 다른 관리자 재거절은 409")
    void notSyncedAndReject() throws Exception {
        mockMvc.perform(post(PATH + "/{id}/approve", exchangeId).with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_APPLICANT_NOT_SYNCED"));
        mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(jsonPath("$.data.applicant").doesNotExist());

        reject(ADMIN, "{\"rejectionReason\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        reject(ADMIN, "{\"rejectionReason\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCHANGE_REJECTION_REASON_INVALID"));
        reject(ADMIN, "{\"rejectionReason\":\" 계좌 불일치 \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 신청을 거절했습니다."))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectedBy").value(ADMIN))
                .andExpect(jsonPath("$.data.rejectionReason").value("계좌 불일치"));
        reject(ADMIN, "{\"rejectionReason\":\"계좌 불일치\"}").andExpect(status().isOk());
        reject(901L, "{\"rejectionReason\":\"계좌 불일치\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_ALREADY_REVIEWED"));
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(INFLUENCER, Role.INFLUENCER)))
                .andExpect(jsonPath("$.data.revenueBalanceHp").value(20_000))
                .andExpect(jsonPath("$.data.heldRevenueHp").value(0));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("저장된 계좌번호를 복호화할 수 없어도 관리자 목록·상세·승인은 200이고 계좌번호를 전부 가리며, 원문·암호문은 응답·로그에 없다")
    void unreadableAccountNumber(CapturedOutput output) throws Exception {
        billingUserSyncUseCase.apply(new UserDto(INFLUENCER, "신청자", Role.INFLUENCER, UserStatus.ACTIVE, 0L));
        String storedCiphertext = jdbcTemplate.queryForObject(
                "SELECT account_number_encrypted FROM billing.exchange_account WHERE exchange_id = ?", String.class,
                exchangeId);
        // 키 변경·암호문 손상과 같은 결과(GCM 인증 실패)를 만든다
        String broken = "v1:" + Base64.getEncoder().encodeToString(new byte[40]);
        jdbcTemplate.update("UPDATE billing.exchange_account SET account_number_encrypted = ? WHERE exchange_id = ?",
                broken, exchangeId);

        String responses = mockMvc.perform(get(PATH).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].account.accountNumberMasked").value("****"))
                .andReturn().getResponse().getContentAsString()
                + mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(ADMIN, Role.ADMIN)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.account.accountNumberMasked").value("****"))
                        .andReturn().getResponse().getContentAsString()
                + mockMvc.perform(post(PATH + "/{id}/approve", exchangeId).with(csrf())
                                .with(TestLogin.as(ADMIN, Role.ADMIN)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.account.accountNumberMasked").value("****"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(responses).doesNotContain(ACCOUNT).doesNotContain(broken).doesNotContain(storedCiphertext)
                .doesNotContain("v1:");
        // 로그를 실제로 잡는지(복호화 실패 경고) 먼저 확인한다
        assertThat(output.getAll()).contains("환전 계좌번호를 복호화하지 못해 전부 가린다")
                .doesNotContain(ACCOUNT).doesNotContain(broken).doesNotContain(storedCiphertext);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("관리자가 아니면 403, 비로그인은 401이고 신청은 바뀌지 않는다")
    void authorization(Role role) throws Exception {
        mockMvc.perform(get(PATH).with(TestLogin.as(INFLUENCER, role))).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(INFLUENCER, role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/{id}/approve", exchangeId).with(csrf()).with(TestLogin.as(INFLUENCER, role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/{id}/reject", exchangeId).with(csrf()).with(TestLogin.as(INFLUENCER, role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rejectionReason\":\"사유\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/{id}/approve", exchangeId).with(csrf())).andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing.exchange WHERE id = ?", String.class,
                exchangeId)).isEqualTo("REQUESTED");
    }

    private ResultActions reject(long adminId, String body) throws Exception {
        return mockMvc.perform(post(PATH + "/{id}/reject", exchangeId).with(csrf())
                .with(TestLogin.as(adminId, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
