package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

// 내 지갑 조회 API의 RsData 성공 응답·지갑 자동 생성·인증·인가(ProblemDetail 오류)를 MockMvc로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1WalletControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("내 지갑을 조회하면 200과 RsData(statusCode·message)의 data에 캐시 잔액·가용 합계·보류 합계를 담아 반환한다")
    void me() throws Exception {
        Wallet wallet = walletRepository.save(Wallet.create(1L));
        jdbcTemplate.update("""
                UPDATE billing.wallet
                SET charge_balance_hp = 3000, revenue_balance_hp = 2000, held_charge_hp = 700, held_revenue_hp = 300
                WHERE id = ?""", wallet.getId());

        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("내 지갑을 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.walletId").value(wallet.getId()))
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(3000))
                .andExpect(jsonPath("$.data.revenueBalanceHp").value(2000))
                .andExpect(jsonPath("$.data.heldChargeHp").value(700))
                .andExpect(jsonPath("$.data.heldRevenueHp").value(300))
                .andExpect(jsonPath("$.data.availableTotalHp").value(5000))
                .andExpect(jsonPath("$.data.heldTotalHp").value(1000));
    }

    @Test
    @DisplayName("지갑이 없는 기존 회원이 조회하면 잔액이 0인 지갑을 만들어 200(RsData)으로 반환하고, 다시 조회하면 같은 지갑이다")
    void me_createsMissingWallet() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.walletId").isNumber())
                .andExpect(jsonPath("$.data.chargeBalanceHp").value(0))
                .andExpect(jsonPath("$.data.revenueBalanceHp").value(0))
                .andExpect(jsonPath("$.data.heldChargeHp").value(0))
                .andExpect(jsonPath("$.data.heldRevenueHp").value(0))
                .andExpect(jsonPath("$.data.availableTotalHp").value(0))
                .andExpect(jsonPath("$.data.heldTotalHp").value(0));

        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walletId").value(walletId));
    }

    @Test
    @DisplayName("다른 회원의 지갑이 아니라 로그인한 회원 자신의 지갑을 반환한다")
    void me_ownWalletOnly() throws Exception {
        Wallet other = walletRepository.save(Wallet.create(2L));

        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk());

        Wallet mine = walletRepository.findByUserId(1L).orElseThrow();
        assertThat(mine.getId()).isNotEqualTo(other.getId());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"INFLUENCER", "VERIFIED_INFLUENCER"})
    @DisplayName("인플루언서·인증된 인플루언서도 자기 지갑을 조회할 수 있다")
    void me_influencers(Role role) throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비로그인으로 조회하면 Security 필터가 401로 막고 RsData 성공 본문을 주지 않는다")
    void me_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("관리자가 조회하면 Facade 역할 검사(@PreAuthorize)로 403을 반환하고 지갑을 만들지 않는다")
    void me_admin() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isForbidden())
                // 오류는 RsData가 아니라 global 공통 핸들러의 ProblemDetail이다.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.statusCode").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(walletRepository.findByUserId(1L)).isEmpty();
    }

    @Test
    @DisplayName("Swagger 명세의 200 응답 스키마는 실제 응답과 같은 RsData<WalletResponse>다")
    void swaggerMatchesResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/wallets/me'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataWalletResponse"))
                .andExpect(jsonPath("$.components.schemas.RsDataWalletResponse.properties.statusCode").exists())
                .andExpect(jsonPath("$.components.schemas.RsDataWalletResponse.properties.message").exists())
                .andExpect(jsonPath("$.components.schemas.RsDataWalletResponse.properties.data['$ref']")
                        .value("#/components/schemas/WalletResponse"));
    }
}
