package com.hapbang.billing.in;

import static com.hapbang.billing.fixture.ChargeFixture.paymentKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.billing.app.ChargeConfirmUseCase;
import com.hapbang.billing.app.ChargeOrderUseCase;
import com.hapbang.billing.app.ChargeResult;
import com.hapbang.billing.app.RefundRequestUseCase;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.fixture.FakeTossPaymentsServer;
import com.hapbang.billing.fixture.FakeTossPaymentsServer.Fault;
import com.hapbang.billing.fixture.GatewayLogin;
import com.hapbang.billing.fixture.WalletMemberFixture;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.OutboundCircuitBreakers;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
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
    BillingUserRepository billingUserRepository;

    @Autowired
    ChargeOrderUseCase chargeOrderUseCase;

    @Autowired
    ChargeConfirmUseCase chargeConfirmUseCase;

    @Autowired
    RefundRequestUseCase refundRequestUseCase;

    @Autowired
    BillingUserSyncUseCase billingUserSyncUseCase;

    @Autowired
    OutboundCircuitBreakers circuits;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String orderId;

    @AfterEach
    void closeTossCircuit() {
        // 회로는 컨텍스트를 함께 쓰는 다른 테스트에도 남으므로 강제로 연 상태를 되돌린다 (#562)
        circuits.circuitBreaker(OutboundCircuitBreakers.Target.TOSS).transitionToClosedState();
    }

    @BeforeEach
    void setUp() {
        TOSS.reset();
        walletRepository.save(Wallet.create(1L));
        walletRepository.save(Wallet.create(2L));
        billingUserRepository.saveAndFlush(WalletMemberFixture.active(1L, Role.USER));
        billingUserRepository.saveAndFlush(WalletMemberFixture.active(2L, Role.USER));
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
        mockMvc.perform(get("/api/v1/wallets/refunds/{id}", requestId).with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isString());
        mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(1L, Role.USER)))
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
        mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(10_000));
    }

    @Test
    @DisplayName("금액·멱등키 형식 오류는 400, 충전 HP 부족은 409, 남의 요청 조회·취소는 404, PG 취소를 시작한 요청 취소는 409다")
    void request_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/refunds").with(GatewayLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, "bad:key", 3_000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_IDEMPOTENCY_KEY_INVALID"));
        request(1L, "key-1", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, "key-1", -1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        request(1L, "key-1", 11_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(TOSS.cancelCount()).isZero();

        TOSS.nextCancel(orderId, Fault.UNREACHABLE);
        Integer requestId = JsonPath.read(request(1L, "key-2", 3_000).andReturn().getResponse().getContentAsString(),
                "$.data.refundRequestId");
        mockMvc.perform(get("/api/v1/wallets/refunds/{id}", requestId).with(GatewayLogin.as(2L, Role.USER)))
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
        mockMvc.perform(get("/api/v1/wallets/me").with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(10_000));
        assertThat(TOSS.cancelCount()).isZero();
    }

    @Test
    @DisplayName("비로그인은 401, 관리자는 403(FORBIDDEN)이고 환불 요청을 만들지 않는다")
    void authorization() throws Exception {
        mockMvc.perform(post("/api/v1/wallets/refunds").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/wallets/refunds").with(GatewayLogin.as(1L, Role.ADMIN))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class))
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 지갑 회원 확인 (#537, BD-131): 환불 신청은 정지 회원도 할 수 있고 탈퇴 회원은 막는다
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("지갑 회원이 없으면 환불 신청은 404 WALLET_MEMBER_NOT_FOUND, 탈퇴 회원은 403 WALLET_MEMBER_NOT_ALLOWED이고 요청을 만들지 않는다")
    void walletMember_notAllowed() throws Exception {
        walletRepository.save(Wallet.create(3L));
        walletRepository.save(Wallet.create(4L));
        billingUserRepository.saveAndFlush(WalletMemberFixture.member(4L, Role.USER, UserStatus.WITHDRAWN));

        request(3L, "key-1", 1_000).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_MEMBER_NOT_FOUND"));
        request(4L, "key-1", 1_000).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WALLET_MEMBER_NOT_ALLOWED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("정지 회원도 환불을 신청할 수 있다(결제한 돈을 돌려받는 것이라 정지로 막지 않는다)")
    void walletMember_suspendedCanRefund() throws Exception {
        billingUserSyncUseCase.apply(new UserDto(1L, "회원1", Role.USER, UserStatus.SUSPENDED, 2L));

        request(1L, "key-1", 10_000).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("TossPayments 회로가 열려 있으면 환불 신청은 접수 전에 503 REFUND_UNAVAILABLE이고 충전 HP를 보류하지 않는다 (#562)")
    void circuitOpen() throws Exception {
        circuits.circuitBreaker(OutboundCircuitBreakers.Target.TOSS).transitionToForcedOpenState();

        request(1L, "key-1", 1_000).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFUND_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class)).isZero();
    }

    private ResultActions request(Long userId, String key, long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/refunds").with(GatewayLogin.as(userId, Role.USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }

    private ResultActions cancel(Long userId, Number requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/wallets/refunds/{id}/cancel", requestId)
                .with(GatewayLogin.as(userId, Role.USER)));
    }
}
