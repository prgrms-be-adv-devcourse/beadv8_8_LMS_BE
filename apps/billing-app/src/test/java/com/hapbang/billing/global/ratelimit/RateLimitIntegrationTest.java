package com.hapbang.billing.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.GatewayLogin;
import com.hapbang.billing.fixture.WalletMemberFixture;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 회원별 요청 한도(#627, BD-140)를 실제 보안 필터·인터셉터·공통 핸들러를 거쳐 확인한다. 한도는 작게(신청·취소 2, 일반 조회 3,
 * 수익 조회 1) 두고, 다른 테스트와 카운터를 섞지 않게 이 클래스만 쓰는 컨텍스트를 띄워 끝나면 닫는다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@DirtiesContext
class RateLimitIntegrationTest {

    private static final FakeTossPaymentsServer TOSS = new FakeTossPaymentsServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ChargeFixture.register(registry, TOSS);
        registry.add("hapbang.billing.rate-limit.write", () -> 2);
        registry.add("hapbang.billing.rate-limit.read", () -> 3);
        registry.add("hapbang.billing.rate-limit.earning", () -> 1);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    BillingUserRepository billingUserRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @AfterAll
    static void tearDown() {
        TOSS.close();
    }

    // 카운터는 컨텍스트에 남으므로 테스트마다 다른 회원을 쓴다
    private static long nextUserId = 1_000L;

    private long userId;

    @BeforeEach
    void setUp() {
        TOSS.reset();
        userId = nextUserId++;
        walletRepository.save(Wallet.create(userId));
        billingUserRepository.saveAndFlush(WalletMemberFixture.active(userId, Role.USER));
    }

    @Test
    @DisplayName("한도를 넘은 충전 주문은 429 TOO_MANY_REQUESTS와 Retry-After를 받고, 주문을 만들지 않는다")
    void overLimitIsRejectedWithoutStateChange() throws Exception {
        createOrder(userId).andExpect(status().isCreated());
        createOrder(userId).andExpect(status().isCreated());

        createOrder(userId)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.point_charge c JOIN billing.wallet w ON w.id = c.wallet_id WHERE w.user_id = ?",
                Integer.class, userId);
        assertThat(orders).isEqualTo(2);
        String retryAfter = createOrder(userId).andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("한 API의 한도를 다 써도 같은 회원의 다른 API와 다른 회원은 막히지 않는다")
    void countersAreSeparate() throws Exception {
        createOrder(userId).andExpect(status().isCreated());
        createOrder(userId).andExpect(status().isCreated());
        createOrder(userId).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(userId, Role.USER)))
                .andExpect(status().isOk());
        long other = userId + 500_000;
        walletRepository.save(Wallet.create(other));
        billingUserRepository.saveAndFlush(WalletMemberFixture.active(other, Role.USER));
        createOrder(other).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("일반 조회는 설정한 한도(3), 수익 조회는 설정한 한도(1)를 쓴다")
    void readAndEarningLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(userId, Role.USER)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(userId, Role.USER)))
                .andExpect(status().isTooManyRequests());

        // 첫 수익 조회는 한도 안이다(출처 응답과 관계없이 429가 아니다)
        int first = mockMvc.perform(get("/api/v1/settlements/earnings/summary").with(GatewayLogin.as(userId, Role.USER)))
                .andReturn().getResponse().getStatus();
        assertThat(first).isNotEqualTo(429);
        mockMvc.perform(get("/api/v1/settlements/earnings/summary").with(GatewayLogin.as(userId, Role.USER)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("한도를 걸지 않은 관리자 API는 한도보다 많이 불러도 막히지 않는다")
    void adminApiIsNotLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/settlements/admin/platform-wallet").with(GatewayLogin.as(userId, Role.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("비로그인 요청은 세기 전에 보안 설정이 401로 막는다")
    void anonymousIsUnauthorized() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("거절 횟수가 API별 지표에 쌓인다")
    void rejectedMetric() throws Exception {
        double before = rejected("exchange-cancel");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/settlements/exchanges/999999/cancel").with(GatewayLogin.as(userId, Role.INFLUENCER)));
        }

        assertThat(rejected("exchange-cancel") - before).isEqualTo(1);
    }

    private double rejected(String api) {
        return meterRegistry.get(UserRateLimiter.REJECTED_METRIC).tag("api", api).functionCounter().count();
    }

    private ResultActions createOrder(long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/charges").with(GatewayLogin.as(userId, Role.USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"));
    }
}
