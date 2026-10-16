package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.app.MockPayoutBank;
import com.hapbang.billing.app.MockPayoutScenarios;
import com.hapbang.billing.out.MockPayoutAccessKey;
import com.hapbang.billing.out.MockPayoutHttpClient;
import com.hapbang.billing.out.PayoutTransferRequest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 가상 송금 Mock이 꺼진 환경(운영·dev와 같은 기본 설정, #288)에서는 Mock 은행 API·시나리오 제어·전용 보안 체인이 없고,
// 송금 Client도 HTTP를 보내지 않는지 확인한다.
@IntegrationTest
@AutoConfigureMockMvc
class MockPayoutDisabledTest {

    private static final String PATH = "/api/v1/wallets/mock-payouts";

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MockPayoutHttpClient client;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("꺼져 있으면 Mock 은행·시나리오 제어·컨트롤러·전용 보안 체인 빈이 없다")
    void noMockBeans() {
        assertThat(context.getBeansOfType(MockPayoutBank.class)).isEmpty();
        assertThat(context.getBeansOfType(MockPayoutScenarios.class)).isEmpty();
        assertThat(context.getBeansOfType(MockPayoutController.class)).isEmpty();
        assertThat(context.containsBean("mockPayoutFilterChain")).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("꺼져 있으면 경로가 없다: 비로그인은 401(global 보안), 로그인해도 404이고 접근 키 헤더로도 열리지 않는다")
    void endpointsDisabled() throws Exception {
        String body = "{\"payoutRequestId\":\"" + UUID.randomUUID() + "\",\"exchangeId\":1,\"amountKrw\":1000,"
                + "\"bankCode\":\"004\",\"accountNumber\":\"12345678\",\"accountHolderName\":\"홍길동\"}";

        mockMvc.perform(post(PATH).with(csrf()).header(MockPayoutAccessKey.HEADER, "any")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).with(csrf()).with(TestLogin.as(1L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID()).with(TestLogin.as(1L, Role.INFLUENCER)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.mock_payout_transfer", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("꺼져 있으면 송금 Client는 HTTP를 보내지 않고 바로 거절한다")
    void clientDisabled() {
        PayoutTransferRequest request = new PayoutTransferRequest(UUID.randomUUID().toString(), 1L, 1_000L, "004",
                "12345678", "홍길동");

        assertThatThrownBy(() -> client.send(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.find(request.payoutRequestId())).isInstanceOf(IllegalStateException.class);
    }
}
