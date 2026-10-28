package com.hapbang.billing.in;

import static com.hapbang.billing.fixture.ChargeFixture.paymentKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.hapbang.billing.app.ChargeConfirmUseCase;
import com.hapbang.billing.app.ChargeOrderUseCase;
import com.hapbang.billing.app.ChargeResult;
import com.hapbang.billing.app.RefundRequestUseCase;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

// 충전 HP 환불 API(#260)의 RsData 응답·상태 코드(200·202)·검증 오류·인증·인가를 MockMvc와 가짜 TossPayments 서버로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1WalletRefundControllerTest {

    private static final FakeTossPaymentsServer TOSS = new FakeTossPaymentsServer();

    @DynamicPropertySource
    static void refundProperties(DynamicPropertyRegistry registry) {
        ChargeFixture.register(registry, TOSS);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    ChargeOrderUseCase chargeOrderUseCase;

    @Autowired
    ChargeConfirmUseCase chargeConfirmUseCase;

    @Autowired
    RefundRequestUseCase refundRequestUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String orderId;

    @BeforeEach
    void setUp() {
        TOSS.reset();
        walletRepository.save(Wallet.create(1L));
        walletRepository.save(Wallet.create(2L));
        ChargeResult order = chargeOrderUseCase.create(1L, 10_000L);
        TOSS.authorize(order.orderId(), paymentKey(order.orderId()), 10_000L);
        chargeConfirmUseCase.confirm(1L, paymentKey(order.orderId()), order.orderId(), 10_000L);
        orderId = order.orderId();
    }

    @AfterAll
    static void tearDown() {
        TOSS.close();
    }

    @Test
    @DisplayName("환불이 끝나면 200 COMPLETED이고 같은 Idempotency-Key 재요청은 같은 요청이며, 조회·지갑에 반영된다")
    void request() throws Exception {
        String body = request(1L, "key-1", 3_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("환불 요청을 처리했습니다."))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.requestedAmount").value(3_000))
                .andExpect(jsonPath("$.data.completedAmount").value(3_000))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.items[0].amount").value(3_000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(paymentKey(orderId));
        Integer requestId = JsonPath.read(body, "$.data.refundRequestId");

        request(1L, "key-1", 3_000).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundRequestId").value(requestId));
        mockMvc.perform(get("/api/v1/wallets/refunds/{id}", requestId).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isString());
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(7_000));
        assertThat(TOSS.cancelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 취소 결과를 확정하지 못하면 202 PROCESSING이고 보류 금액을 pendingAmount로 알려 준다")
    void request_processing() throws Exception {
        TOSS.nextCancel(orderId, Fault.UNREACHABLE);

        request(1L, "key-1", 3_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.message").value("환불 결과를 확인하고 있습니다."))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.pendingAmount").value(3_000))
                .andExpect(jsonPath("$.data.items[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].failureReason").doesNotExist());
    }

    @Test
    @DisplayName("PG 취소가 요청 오류로 거절되면 200 FAILED이고 보류 HP를 모두 반환한다")
    void request_failed() throws Exception {
        TOSS.nextCancel(orderId, Fault.INVALID_REQUEST);

        request(1L, "key-1", 3_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.returnedAmount").value(3_000));
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(10_000));
    }

    @Test
    @DisplayName("금액·멱등키 형식 오류는 400, 충전 HP 부족은 409, 남의 요청 조회·취소는 404, PG 취소를 시작한 요청 취소는 409다")
    void request_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/refunds").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, "bad:key", 3_000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_IDEMPOTENCY_KEY_INVALID"));
        request(1L, "key-1", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, "key-1", 1_500).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_INVALID"));
        request(1L, "key-1", 11_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(TOSS.cancelCount()).isZero();

        TOSS.nextCancel(orderId, Fault.UNREACHABLE);
        Integer requestId = JsonPath.read(request(1L, "key-2", 3_000).andReturn().getResponse().getContentAsString(),
                "$.data.refundRequestId");
        mockMvc.perform(get("/api/v1/wallets/refunds/{id}", requestId).with(TestLogin.as(2L, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
        cancel(2L, requestId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
        cancel(1L, requestId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_CANCELABLE"));
    }

    @Test
    @DisplayName("PG 취소 전(REQUESTED) 요청은 취소하면 200 CANCELED이고 보류 HP를 모두 반환한다")
    void cancel() throws Exception {
        Long requestId = refundRequestUseCase.create(1L, "key-1", 3_000L);

        cancel(1L, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("환불 요청을 취소했습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.returnedAmount").value(3_000));
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(10_000));
        assertThat(TOSS.cancelCount()).isZero();
    }

    @Test
    @DisplayName("비로그인은 401, 관리자는 403(FORBIDDEN)이고 환불 요청을 만들지 않는다")
    void authorization() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/refunds").with(csrf()).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/wallets/refunds").with(csrf()).with(TestLogin.as(1L, Role.ADMIN))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class))
                .isZero();
    }

    private ResultActions request(Long userId, String key, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/refunds").with(csrf()).with(TestLogin.as(userId, Role.USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }

    private ResultActions cancel(Long userId, Number requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/refunds/{id}/cancel", requestId).with(csrf())
                .with(TestLogin.as(userId, Role.USER)));
    }
}
