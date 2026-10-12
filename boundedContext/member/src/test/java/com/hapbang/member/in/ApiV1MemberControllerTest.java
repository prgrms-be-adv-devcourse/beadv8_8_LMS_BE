package com.hapbang.member.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.shared.member.domain.Role;
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

    @Test
    @DisplayName("로컬 로그인 설정이 꺼져 있으면(서버) 로컬 로그인 주소가 없다")
    void localLogin_disabled() throws Exception {
        mockMvc.perform(post("/api/v1/members/local-login?role=ADMIN").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("닉네임을 수정하면 200과 수정된 내 정보를 반환하고 DB에도 저장된다")
    void updateMe() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새 닉네임"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("새 닉네임");
    }

    @Test
    @DisplayName("닉네임 없이 수정하면 200이고 닉네임은 그대로다")
    void updateMe_withoutNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("tester1"));
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임이면 400과 오류 코드를 ProblemDetail로 반환한다")
    void updateMe_invalidNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"민\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME_LENGTH"))
                .andExpect(jsonPath("$.detail").value("닉네임은 2~12자로 입력해 주세요."));
    }

    @Test
    @DisplayName("비로그인으로 수정하면 401을 반환한다")
    void updateMe_notLoggedIn() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 없이 수정하면 403을 반환한다")
    void updateMe_withoutCsrfToken() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 회원으로 내 정보를 조회하면 404와 MEMBER_NOT_FOUND를 반환한다")
    void me_memberNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/members/me").with(TestLogin.as(999L, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }
}
