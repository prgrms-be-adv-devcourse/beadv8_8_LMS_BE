package com.hapbang.billing.in;

import static com.hapbang.billing.fixture.ChargeFixture.paymentKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

// HP 충전 API(#224)의 RsData 응답·상태 코드(201·200·202)·검증 오류·인증·인가를 MockMvc와 가짜 TossPayments 서버로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1WalletChargeControllerTest {

    private static final FakeTossPaymentsServer TOSS = new FakeTossPaymentsServer();

    @DynamicPropertySource
    static void chargeProperties(DynamicPropertyRegistry registry) {
        ChargeFixture.register(registry, TOSS);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TOSS.reset();
        walletRepository.save(Wallet.create(1L));
    }

    @AfterAll
    static void tearDown() {
        TOSS.close();
    }

    @Test
    @DisplayName("충전 주문을 만들면 201과 결제창에 쓸 orderId·orderName·amount를 RsData로 돌려준다")
    void createOrder() throws Exception {
        createOrder(1L, 10_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("충전 주문을 만들었습니다."))
                .andExpect(jsonPath("$.data.orderId").isString())
                .andExpect(jsonPath("$.data.orderName").value("HP 10,000 충전"))
                .andExpect(jsonPath("$.data.amount").value(10_000))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("금액이 없거나 0 이하면 400 INVALID_REQUEST, 1,000 단위가 아니면 400 CHARGE_AMOUNT_INVALID다")
    void createOrder_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/charges").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        createOrder(1L, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        createOrder(1L, 1_500).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGE_AMOUNT_INVALID"));
    }

    @Test
    @DisplayName("결제 승인이 끝나면 200 COMPLETED이고 응답·조회에 결제키를 담지 않으며, 같은 요청을 다시 보내도 200으로 같은 결과다")
    void confirm() throws Exception {
        String orderId = orderId(createOrder(1L, 10_000));

        String body = confirm(1L, orderId, 10_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("HP를 충전했습니다."))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.amount").value(10_000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(paymentKey(orderId));

        confirm(1L, orderId, 10_000).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mockMvc.perform(get("/api/v1/wallets/charges/{orderId}", orderId).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isString());
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(10_000));
        assertThat(TOSS.confirmCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("승인 결과를 확정하지 못하면 202 PROCESSING이다(같은 요청 재전송 또는 조회로 확인)")
    void confirm_processing() throws Exception {
        String orderId = orderId(createOrder(1L, 10_000));
        TOSS.nextConfirm(orderId, Fault.SERVER_ERROR);
        TOSS.nextLookup(orderId, Fault.SERVER_ERROR);

        confirm(1L, orderId, 10_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.message").value("결제 승인 결과를 확인하고 있습니다."))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                // 결과 미확정 사유는 운영자 확인용이라 응답에 내보내지 않는다
                .andExpect(jsonPath("$.data.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("PG가 승인하지 않으면 400 CHARGE_PAYMENT_REJECTED(detail = PG 메시지, pgCode)이고, 다시 보내면 409 CHARGE_ALREADY_FAILED다")
    void confirm_rejected() throws Exception {
        String orderId = orderId(createOrder(1L, 10_000));
        TOSS.nextConfirm(orderId, Fault.REJECT_CARD);

        confirm(1L, orderId, 10_000)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CHARGE_PAYMENT_REJECTED"))
                .andExpect(jsonPath("$.detail").value("한도초과 혹은 잔액부족으로 결제에 실패했습니다."))
                .andExpect(jsonPath("$.pgCode").value("REJECT_CARD_PAYMENT"));
        confirm(1L, orderId, 10_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_ALREADY_FAILED"));
    }

    @Test
    @DisplayName("승인 요청 값이 형식에 맞지 않으면 400 INVALID_REQUEST, 금액이 주문과 다르면 400 CHARGE_AMOUNT_MISMATCH, 남의 주문은 404다")
    void confirm_invalid() throws Exception {
        String orderId = orderId(createOrder(1L, 10_000));
        walletRepository.save(Wallet.create(2L));

        mockMvc.perform(post("/api/v1/wallets/charges/confirm").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"k\",\"orderId\":\"bad id!\",\"amount\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        confirm(1L, orderId, 5_000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGE_AMOUNT_MISMATCH"));
        confirm(2L, orderId, 10_000).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHARGE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/wallets/charges/{orderId}", orderId).with(TestLogin.as(2L, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHARGE_NOT_FOUND"));
        assertThat(TOSS.confirmCount()).isZero();
    }

    @Test
    @DisplayName("비로그인은 401, 관리자는 403(FORBIDDEN)이고 주문을 만들지 않는다")
    void authorization() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/charges").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isUnauthorized());
        createOrder(1L, Role.ADMIN, 10_000)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge", Integer.class)).isZero();
    }

    private ResultActions createOrder(Long userId, long amount) throws Exception {
        return createOrder(userId, Role.USER, amount);
    }

    private ResultActions createOrder(Long userId, Role role, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/charges").with(csrf()).with(TestLogin.as(userId, role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }

    // 결제창 인증까지 마친 것으로 보고 가짜 PG에 결제를 등록한 뒤 주문 ID를 돌려준다
    private String orderId(ResultActions created) throws Exception {
        String orderId = JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.data.orderId");
        long amount = ((Number) JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.data.amount"))
                .longValue();
        TOSS.authorize(orderId, paymentKey(orderId), amount);
        return orderId;
    }

    private ResultActions confirm(Long userId, String orderId, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/charges/confirm").with(csrf())
                .with(TestLogin.as(userId, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"" + paymentKey(orderId) + "\",\"orderId\":\"" + orderId
                        + "\",\"amount\":" + amount + "}"));
    }
}
