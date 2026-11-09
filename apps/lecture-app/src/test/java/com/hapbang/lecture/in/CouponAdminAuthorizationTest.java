package com.hapbang.lecture.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 강의 앱 전체 컨텍스트(실제 SecurityConfig와 GatewayUserHeaderFilter)에서 같은 jar에 실린 쿠폰 관리자 경로가
// 사용자 헤더 없이는 401, 관리자가 아니면 403이고 관리자만 통과하는지 본다. 쿠폰 코드는 HTTP로만 부른다
@IntegrationTest
@AutoConfigureMockMvc
class CouponAdminAuthorizationTest {

    private static final String ADMIN_COUPONS = "/api/v1/coupons/admin";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 사용자_헤더가_없으면_401이다() throws Exception {
        mockMvc.perform(get(ADMIN_COUPONS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN_COUPONS + "/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자가_아닌_회원은_403이다() throws Exception {
        mockMvc.perform(get(ADMIN_COUPONS).with(GatewayLogin.as(7L, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN_COUPONS + "/1").with(GatewayLogin.as(7L, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_쿠폰_목록을_조회한다() throws Exception {
        mockMvc.perform(get(ADMIN_COUPONS).with(GatewayLogin.as(1L, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
