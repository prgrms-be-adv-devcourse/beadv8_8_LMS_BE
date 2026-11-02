package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** 이메일·비밀번호 가입·로그인 API (#499). 실제 DB·Redis와 보안 설정으로 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class ApiV1MemberPasswordControllerTest {

    private static final String SIGNUP = "/api/v1/members/signup";
    private static final String LOGIN = "/api/v1/members/login";
    private static final String REFRESH = "/api/v1/members/auth/refresh";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트가 만든 Redis 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("가입하면 201과 내 정보를 주고, 비밀번호는 해시로만 저장한다 (로그인 없이, CSRF 없이)")
    void signUp() throws Exception {
        String email = newEmail();

        signUp(email.toUpperCase(), "abcd1234", "tester")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        User user = userRepository.findByEmail(email).orElseThrow();
        trackUser(user.getId());
        assertThat(passwordCredentialRepository.findByUserId(user.getId()))
                .hasValueSatisfying(c -> assertThat(c.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("abcd1234"));
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 DUPLICATE_EMAIL")
    void signUp_duplicate() throws Exception {
        String email = signedUp("abcd1234");

        signUp(email, "other1234", "tester")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @DisplayName("비밀번호 규칙을 어기면 400, 이메일 형식이 틀리면 400 INVALID_REQUEST")
    void signUp_invalid() throws Exception {
        signUp(newEmail(), "abcdefgh", "tester")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_LETTER_AND_DIGIT_REQUIRED"));
        signUp("not-an-email", "abcd1234", "tester")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("로그인하면 refresh token 쿠키를 주고, 그 쿠키로 access token을 재발급받는다")
    void login() throws Exception {
        String email = signedUp("abcd1234");

        String setCookie = login(email, "abcd1234")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).startsWith(RefreshTokenCookies.NAME + "=")
                .contains("Path=/api/v1/members/auth", "HttpOnly", "Secure", "SameSite=Strict");
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(cookieValue(setCookie))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("틀린 비밀번호와 없는 이메일은 같은 401 INVALID_CREDENTIALS이고 쿠키가 없다")
    void login_invalidCredentials() throws Exception {
        String email = signedUp("abcd1234");

        login(email, "wrong1234")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull());
        login(newEmail(), "abcd1234")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("5번 연속 틀리면 맞는 비밀번호도 429 LOGIN_LOCKED")
    void login_locked() throws Exception {
        String email = signedUp("abcd1234");
        for (int i = 0; i < 5; i++) {
            login(email, "wrong1234").andExpect(status().isUnauthorized());
        }

        login(email, "abcd1234")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
    }

    private ResultActions signUp(String email, String password, String nickname) throws Exception {
        return mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\"}".formatted(email, password, nickname)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    /** 가입하고 이메일을 돌려준다 */
    private String signedUp(String password) throws Exception {
        String email = newEmail();
        signUp(email, password, "tester").andExpect(status().isCreated());
        trackUser(userRepository.findByEmail(email).orElseThrow().getId());
        return email;
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = sha256(email);
        createdKeys.add("member:login-fail:" + hash);
        createdKeys.add("member:login-lock:" + hash);
        return email;
    }

    private void trackUser(Long userId) {
        createdKeys.add("member:user-refresh:" + userId);
    }

    private static Cookie refreshCookie(String value) {
        return new Cookie(RefreshTokenCookies.NAME, value);
    }

    private String cookieValue(String setCookie) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        createdKeys.add("member:refresh:" + sha256(value));
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
