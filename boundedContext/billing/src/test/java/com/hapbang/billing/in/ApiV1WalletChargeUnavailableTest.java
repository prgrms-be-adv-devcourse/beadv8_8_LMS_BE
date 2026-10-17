package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// TossPayments 시크릿 키·결제키 암호화 키가 없는 환경(설정 전 서버)에서는 앱이 뜨고 충전·환불·환전 신청 API만 503으로 거절하는지 확인한다 (#224, #260, #285).
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1WalletChargeUnavailableTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("결제 설정이 없으면 충전 주문·승인은 503 CHARGE_UNAVAILABLE이고 주문을 만들지 않는다")
    void unavailable() throws Exception {
        walletRepository.save(Wallet.create(1L));

        mockMvc.perform(post("/api/v1/wallets/charges").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHARGE_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/wallets/charges/confirm").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"tgen_x\",\"orderId\":\"HPCHG-order1\",\"amount\":10000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHARGE_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_charge", Integer.class)).isZero();
    }

    @Test
    @DisplayName("결제 설정이 없으면 환불 요청은 503 REFUND_UNAVAILABLE이고 요청을 만들지 않는다 (#260)")
    void refundUnavailable() throws Exception {
        walletRepository.save(Wallet.create(1L));

        mockMvc.perform(post("/api/v1/wallets/refunds").with(csrf()).with(TestLogin.as(1L, Role.USER))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFUND_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_refund_request", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("계좌번호 암호화 키가 없으면 환전 신청은 503 EXCHANGE_UNAVAILABLE이고 신청·보류를 만들지 않는다 (#285)")
    void exchangeUnavailable() throws Exception {
        walletRepository.save(Wallet.create(1L));

        mockMvc.perform(post("/api/v1/settlements/exchanges").with(csrf()).with(TestLogin.as(1L, Role.INFLUENCER))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000,\"bankCode\":\"004\",\"accountNumber\":\"12345678\","
                                + "\"accountHolderName\":\"홍길동\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXCHANGE_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.exchange", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.point_hold", Integer.class)).isZero();
    }
}
