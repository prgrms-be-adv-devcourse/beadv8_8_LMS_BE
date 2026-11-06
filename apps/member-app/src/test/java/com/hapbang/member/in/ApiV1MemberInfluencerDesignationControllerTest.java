package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;

/**
 * 관리자 인플루언서 지정 API (#724): 보안 설정의 URL 권한·응답 모양 (처리 규칙 자세한 경우는 MemberInfluencerDesignationIntegrationTest).
 * 대상 이메일 제한이 없어져 URL 단의 ADMIN 제한이 주된 방어선이라 실제 진입 경로로 확인한다
 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberInfluencerDesignationControllerTest {

    private static final String MAKE_INFLUENCER = "/api/v1/members/admin/users/{userId}/make-influencer";
    private static final Long ADMIN_ID = 99L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("관리자가 부르면 200이고 회원이 인플루언서가 된다 (changed=true)")
    void makeInfluencer() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        makeInfluencer(GatewayLogin.as(ADMIN_ID, Role.ADMIN), user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.role").value("INFLUENCER"))
                .andExpect(jsonPath("$.data.changed").value(true));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인하지 않으면 401이고 역할은 그대로다 (/admin/** URL 규칙이 막아 본문이 없다)")
    void makeInfluencer_forbidden() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        // Facade의 @PreAuthorize가 막으면 ProblemDetail 본문(code=FORBIDDEN)이 온다.
        // 본문이 없어야 보안 필터의 URL 규칙(/api/v1/*/admin/**)에서 막힌 것이다 (docs/api/conventions.md)
        makeInfluencer(GatewayLogin.as(user.getId(), Role.USER), user.getId())
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        makeInfluencer(GatewayLogin.as(1L, Role.VERIFIED_INFLUENCER), user.getId())
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        makeInfluencer(null, user.getId())
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("옛 경로 /api/v1/members/test/{userId}/influencer는 관리자가 불러도 404이고 역할은 그대로다")
    void oldPath_removed() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        mockMvc.perform(post("/api/v1/members/test/{userId}/influencer", user.getId())
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("관리자 회원을 지정하려 하면 409 INFLUENCER_DESIGNATION_ROLE_NOT_ALLOWED")
    void makeInfluencer_admin() throws Exception {
        User admin = userRepository.save(MemberFixture.member(Role.ADMIN));

        makeInfluencer(GatewayLogin.as(ADMIN_ID, Role.ADMIN), admin.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFLUENCER_DESIGNATION_ROLE_NOT_ALLOWED"));
    }

    private ResultActions makeInfluencer(RequestPostProcessor login, Long userId) throws Exception {
        var request = post(MAKE_INFLUENCER, userId);
        return mockMvc.perform(login == null ? request : request.with(login));
    }
}
