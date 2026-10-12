package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 내 지갑 조회 API의 응답·지갑 자동 생성·인증·인가를 MockMvc로 검증한다.
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
    @DisplayName("내 지갑을 조회하면 200과 캐시 잔액·가용 합계·보류 합계를 반환한다")
    void me() throws Exception {
        Wallet wallet = walletRepository.save(Wallet.create(1L));
        jdbcTemplate.update("""
                UPDATE billing.wallet
                SET charge_balance_hp = 3000, revenue_balance_hp = 2000, held_charge_hp = 700, held_revenue_hp = 300
                WHERE id = ?""", wallet.getId());

        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(wallet.getId()))
                .andExpect(jsonPath("$.chargeBalanceHp").value(3000))
                .andExpect(jsonPath("$.revenueBalanceHp").value(2000))
                .andExpect(jsonPath("$.heldChargeHp").value(700))
                .andExpect(jsonPath("$.heldRevenueHp").value(300))
                .andExpect(jsonPath("$.availableTotalHp").value(5000))
                .andExpect(jsonPath("$.heldTotalHp").value(1000));
    }

    @Test
    @DisplayName("지갑이 없는 기존 회원이 조회하면 잔액이 0인 지갑을 만들어 반환한다")
    void me_createsMissingWallet() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").isNumber())
                .andExpect(jsonPath("$.chargeBalanceHp").value(0))
                .andExpect(jsonPath("$.revenueBalanceHp").value(0))
                .andExpect(jsonPath("$.heldChargeHp").value(0))
                .andExpect(jsonPath("$.heldRevenueHp").value(0))
                .andExpect(jsonPath("$.availableTotalHp").value(0))
                .andExpect(jsonPath("$.heldTotalHp").value(0));

        Long walletId = walletRepository.findByUserId(1L).orElseThrow().getId();
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId));
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
    @DisplayName("비로그인으로 조회하면 401을 반환한다")
    void me_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자가 조회하면 Facade 역할 검사(@PreAuthorize)로 403을 반환하고 지갑을 만들지 않는다")
    void me_admin() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me").with(TestLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isForbidden());

        assertThat(walletRepository.findByUserId(1L)).isEmpty();
    }
}
