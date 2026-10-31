package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

// 환전 신청 API(#285)의 RsData 응답·상태 코드·검증 오류·계좌번호 마스킹·인증·인가를 MockMvc로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1SettlementExchangeControllerTest {

    private static final String PATH = "/api/v1/settlements/exchanges";
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
    JdbcTemplate jdbcTemplate;

    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        revenue(walletRepository.save(Wallet.create(1L)).getId(), 10_000L);
        revenue(walletRepository.save(Wallet.create(2L)).getId(), 10_000L);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("인플루언서·인증된 인플루언서는 신청하면 201 REQUESTED이고 수수료·예상 수령액·마스킹한 계좌를 돌려준다")
    void request(Role role) throws Exception {
        String body = request(1L, role, "key-1", body(3_000, "004", ACCOUNT, "홍길동"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("환전을 신청했습니다."))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.requestedAmount").value(3_000))
                .andExpect(jsonPath("$.data.feeRate").value(0.1))
                .andExpect(jsonPath("$.data.feeAmount").value(300))
                .andExpect(jsonPath("$.data.expectedPayoutAmount").value(2_700))
                .andExpect(jsonPath("$.data.settlementRoundDate").isString())
                .andExpect(jsonPath("$.data.account.bankCode").value("004"))
                .andExpect(jsonPath("$.data.account.accountNumberMasked").value("**********1234"))
                .andExpect(jsonPath("$.data.account.accountHolderName").value("홍길동"))
                .andExpect(jsonPath("$.data.account.verificationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ACCOUNT).doesNotContain("accountNumberEncrypted").doesNotContain("v1:");
        Integer exchangeId = JsonPath.read(body, "$.data.exchangeId");

        // 같은 키 재요청은 같은 신청(201), 다른 금액은 409
        request(1L, role, "key-1", body(3_000, "004", ACCOUNT, "홍길동")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.exchangeId").value(exchangeId));
        request(1L, role, "key-1", body(4_000, "004", ACCOUNT, "홍길동")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(1L, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.accountNumberMasked").value("**********1234"));
        mockMvc.perform(get(PATH).with(TestLogin.as(1L, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 신청 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, role)))
                .andExpect(jsonPath("$.data.revenueBalanceHp").value(7_000))
                .andExpect(jsonPath("$.data.heldRevenueHp").value(3_000));
    }

    @Test
    @DisplayName("취소하면 200 CANCELED이고 수익 HP가 돌아온다. 다시 취소해도 200 CANCELED이며 남의 신청은 404다")
    void cancel() throws Exception {
        Integer exchangeId = JsonPath.read(request(1L, Role.INFLUENCER, "key-1",
                body(3_000, "004", ACCOUNT, "홍길동")).andReturn().getResponse().getContentAsString(), "$.data.exchangeId");

        cancel(2L, exchangeId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXCHANGE_NOT_FOUND"));
        mockMvc.perform(get(PATH + "/{id}", exchangeId).with(TestLogin.as(2L, Role.INFLUENCER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXCHANGE_NOT_FOUND"));
        cancel(1L, exchangeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환전 신청을 취소했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString());
        cancel(1L, exchangeId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.INFLUENCER)))
                .andExpect(jsonPath("$.data.revenueBalanceHp").value(10_000))
                .andExpect(jsonPath("$.data.heldRevenueHp").value(0));

        // 승인 뒤(송금 단계) 신청은 취소할 수 없다. 승인 기능은 후속 Issue라 상태를 직접 바꾼다
        Integer processing = JsonPath.read(request(1L, Role.INFLUENCER, "key-2",
                body(1_000, "004", ACCOUNT, "홍길동")).andReturn().getResponse().getContentAsString(), "$.data.exchangeId");
        jdbcTemplate.update("UPDATE billing.exchange SET status = 'PROCESSING' WHERE id = ?", processing);
        cancel(1L, processing).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXCHANGE_NOT_CANCELABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\":1000,\"bankCode\":\"04\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}",
            "{\"amount\":1000,\"bankCode\":\"abc\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}",
            "{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"1234567\",\"accountHolderName\":\"홍길동\"}",
            "{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"123456789012345678901\",\"accountHolderName\":\"홍길동\"}",
            "{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"1234-5678-90\",\"accountHolderName\":\"홍길동\"}",
            "{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"  \"}",
            "{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"12345678\"}",
            "{\"amount\":0,\"bankCode\":\"004\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}",
            "{\"bankCode\":\"004\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}"})
    @DisplayName("계좌 형식(은행 숫자 3자리·계좌 숫자 8~20자리·예금주)·금액 누락 오류는 400 INVALID_REQUEST이고 아무것도 남기지 않는다")
    void invalidRequest(String body) throws Exception {
        request(1L, Role.INFLUENCER, "key-1", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray());
        assertNothingCreated();
    }

    @Test
    @DisplayName("금액 0 이하 400 INVALID_REQUEST, 멱등키 형식·누락 400, 예금주 50자 초과 400, 수익 HP 부족 409")
    void businessErrors() throws Exception {
        for (long amount : new long[] {0, -1}) {
            request(1L, Role.INFLUENCER, "key-1", body(amount, "004", ACCOUNT, "홍길동"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        request(1L, Role.INFLUENCER, "bad:key", body(1_000, "004", ACCOUNT, "홍길동"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCHANGE_IDEMPOTENCY_KEY_INVALID"));
        mockMvc.perform(post(PATH).with(csrf()).with(TestLogin.as(1L, Role.INFLUENCER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(1_000, "004", ACCOUNT, "홍길동")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, Role.INFLUENCER, "key-1", body(1_000, "004", ACCOUNT, "가".repeat(51)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, Role.INFLUENCER, "key-1", body(11_000, "004", ACCOUNT, "홍길동"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertNothingCreated();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("계좌번호 원문은 성공·검증 오류·충돌·복호화 실패 응답과 로그 어디에도 나오지 않는다")
    void accountNumberNeverExposed(CapturedOutput output) throws Exception {
        String invalidAccount = "9876-5432-10";
        String responses = request(1L, Role.INFLUENCER, "key-1", body(3_000, "004", ACCOUNT, "홍길동"))
                .andReturn().getResponse().getContentAsString()
                + request(1L, Role.INFLUENCER, "key-1", body(3_000, "004", "98765432109876", "홍길동"))
                        .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString()
                + request(1L, Role.INFLUENCER, "key-2", body(3_000, "004", invalidAccount, "홍길동"))
                        .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()
                + mockMvc.perform(get(PATH).with(TestLogin.as(1L, Role.INFLUENCER)))
                        .andReturn().getResponse().getContentAsString();
        jdbcTemplate.update("UPDATE billing.exchange_account SET account_number_encrypted = 'v1:broken'");
        responses += request(1L, Role.INFLUENCER, "key-1", body(3_000, "004", ACCOUNT, "홍길동"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EXCHANGE_ACCOUNT_UNREADABLE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(responses).doesNotContain(ACCOUNT).doesNotContain("98765432109876").doesNotContain(invalidAccount);
        // 로그를 실제로 잡고 있는지(복호화 실패 로그가 남았는지) 먼저 확인한다
        assertThat(output.getAll()).contains("환전 계좌번호를 복호화하지 못해").doesNotContain(ACCOUNT).doesNotContain("98765432109876")
                .doesNotContain(invalidAccount);
    }

    @Test
    @DisplayName("비로그인은 401, 유저·관리자는 403(FORBIDDEN)이고 신청·조회·취소를 할 수 없다")
    void authorization() throws Exception {
        mockMvc.perform(post(PATH).with(csrf()).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body(1_000, "004", ACCOUNT, "홍길동")))
                .andExpect(status().isUnauthorized());
        for (Role role : List.of(Role.USER, Role.ADMIN)) {
            request(1L, role, "key-1", body(1_000, "004", ACCOUNT, "홍길동"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mockMvc.perform(get(PATH).with(TestLogin.as(1L, role)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mockMvc.perform(get(PATH + "/1").with(TestLogin.as(1L, role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(PATH + "/1/cancel").with(csrf()).with(TestLogin.as(1L, role)))
                    .andExpect(status().isForbidden());
        }
        assertNothingCreated();
    }

    private ResultActions request(Long userId, Role role, String key, String body) throws Exception {
        return mockMvc.perform(post(PATH).with(csrf()).with(TestLogin.as(userId, role))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions cancel(Long userId, Integer exchangeId) throws Exception {
        return mockMvc.perform(post(PATH + "/{id}/cancel", exchangeId).with(csrf())
                .with(TestLogin.as(userId, Role.INFLUENCER)));
    }

    private static String body(long amount, String bankCode, String accountNumber, String holder) {
        return "{\"amount\":" + amount + ",\"bankCode\":\"" + bankCode + "\",\"accountNumber\":\"" + accountNumber
                + "\",\"accountHolderName\":\"" + holder + "\"}";
    }

    private void assertNothingCreated() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.exchange", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_hold WHERE reference_type = 'EXCHANGE'", Integer.class)).isZero();
    }

    // 수익 HP 가용 잔액을 늘리는 원장을 기록한다(기초 잔액).
    private void revenue(Long walletId, long amountHp) {
        int id = sequence.incrementAndGet();
        ledgerRecordUseCase.record(new LedgerOperation("SEED:" + walletId + ":" + id, LedgerTransactionType.PAYOUT,
                BillingReferenceType.SETTLEMENT, (long) id, null, Instant.parse("2026-10-01T00:00:00Z"),
                List.of(LedgerEntry.of(walletId, PointType.REVENUE, BalanceBucket.AVAILABLE, amountHp))));
    }
}
