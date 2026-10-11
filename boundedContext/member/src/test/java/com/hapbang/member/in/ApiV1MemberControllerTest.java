package com.hapbang.member.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

@IntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("로그인 상태로 내 정보를 조회하면 200과 회원 정보를 반환한다")
    void me_loggedIn() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(get("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value("tester1@example.com"))
                .andExpect(jsonPath("$.nickname").value("tester1"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("비로그인으로 내 정보를 조회하면 구글로 리다이렉트하지 않고 401을 반환한다")
    void me_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 200을 반환하고 세션 쿠키를 지운다")
    void logout() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(post("/api/v1/members/logout").with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("SESSION", 0));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 로그아웃하면 403을 반환한다")
    void logout_withoutCsrfToken() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(post("/api/v1/members/logout").with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isForbidden());
    }
}
