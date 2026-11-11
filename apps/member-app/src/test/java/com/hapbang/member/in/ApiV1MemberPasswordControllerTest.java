package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.EmailVerificationPurpose;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** 이메일·비밀번호 가입·로그인·비밀번호 변경 API (#499). 실제 DB·Redis와 보안 설정으로 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class ApiV1MemberPasswordControllerTest {

    private static final String SIGNUP = "/api/v1/members/signup";
    private static final String LOGIN = "/api/v1/members/login";
    private static final String CHANGE = "/api/v1/members/me/password";
    private static final String REFRESH = "/api/v1/members/auth/refresh";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Autowired
    EmailVerificationStore emailVerificationStore;

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
    @DisplayName("인증 토큰이 없으면 400, 다른 이메일의 토큰·없는 토큰이면 400 EMAIL_NOT_VERIFIED이고 가입되지 않는다 (#557)")
    void signUp_notVerified() throws Exception {
        String email = newEmail();

        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"abcd1234\",\"nickname\":\"tester\"}".formatted(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        signUp(email, "abcd1234", "tester", verifiedToken(newEmail()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        signUp(email, "abcd1234", "tester", "no-such-token")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("부하 시험 계정 이메일(k6-…@example.invalid)은 인증 토큰 없이 가입된다 (#557 임시 예외)")
    void signUp_loadTestAccountWithoutToken() throws Exception {
        String email = "k6-20261105-101500-%s-001@example.invalid".formatted(UUID.randomUUID().toString().substring(0, 6));
        createdKeys.add("member:login-fail:" + sha256(email));
        createdKeys.add("member:login-lock:" + sha256(email));

        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"abcd1234\",\"nickname\":\"%s\"}"
                                .formatted(email, "k6" + UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated());
        trackUser(userRepository.findByEmail(email).orElseThrow().getId());
    }

    @Test
    @DisplayName("인증 토큰은 가입에 한 번만 쓰이고, 가입이 실패하면(닉네임 중복) 그대로 남아 다시 쓸 수 있다 (#557)")
    void signUp_tokenOnce() throws Exception {
        signedUp("abcd1234");
        String email = newEmail();
        String token = verifiedToken(email);

        signUp(email, "abcd1234", "tester", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        signUp(email, "abcd1234", "tester2", token).andExpect(status().isCreated());
        trackUser(userRepository.findByEmail(email).orElseThrow().getId());
        signUp(newEmail(), "abcd1234", "tester3", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        assertThat(emailVerificationStore.findTokenEmail(token, EmailVerificationPurpose.SIGN_UP)).isEmpty();
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
    @DisplayName("다른 회원이 쓰는 닉네임이면 대소문자만 달라도 409 DUPLICATE_NICKNAME (#646)")
    void signUp_duplicateNickname() throws Exception {
        signedUp("abcd1234");

        signUp(newEmail(), "abcd1234", "TESTER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"))
                .andExpect(jsonPath("$.detail").value("이미 쓰고 있는 닉네임이에요."));
    }

    @Test
    @DisplayName("대문자가 섞인 이메일로 가입한 구글 회원이 있으면 소문자 이메일 가입도 409 DUPLICATE_EMAIL")
    void signUp_duplicateIgnoringCase() throws Exception {
        String email = newEmail();
        User googleMember = userRepository.save(User.join(email.toUpperCase(), "google"));
        trackUser(googleMember.getId());

        signUp(email, "abcd1234", "tester")
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

        List<String> setCookies = login(email, "abcd1234")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String setCookie = setCookies.get(0);

        assertThat(setCookie).startsWith(RefreshTokenCookies.NAME + "=")
                .contains("Path=/api/v1/members/auth", "HttpOnly", "Secure", "SameSite=Strict");
        // 앞사람이 남긴 복구 티켓 쿠키는 지운다 (공용 PC, #633)
        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
                .startsWith(RestoreTicketCookies.NAME + "=;")
                .contains("Max-Age=0", "Path=/api/v1/members/restore"));
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(cookieValue(setCookie))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("마스킹으로 지운 비밀번호는 없는 것과 같아 500이 아니라 401 INVALID_CREDENTIALS다 (#594)")
    void login_wipedCredential() throws Exception {
        String email = signedUp("abcd1234");
        User user = userRepository.findByEmail(email).orElseThrow();
        PasswordCredential credential = passwordCredentialRepository.findByUserId(user.getId()).orElseThrow();
        credential.wipe();
        passwordCredentialRepository.saveAndFlush(credential);

        login(email, PasswordCredential.WIPED_HASH)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
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

    @Test
    @DisplayName("비밀번호를 바꾸면 예전 refresh token은 폐기되고, 이 기기는 새 쿠키를 받고, 새 비밀번호로 로그인된다")
    void changePassword() throws Exception {
        String email = signedUp("abcd1234");
        String oldRefresh = cookieValue(login(email, "abcd1234").andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE));
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();

        String newCookie = mockMvc.perform(patch(CHANGE).with(GatewayLogin.as(userId, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"abcd1234\",\"newPassword\":\"newpass5678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        mockMvc.perform(post(REFRESH).cookie(refreshCookie(oldRefresh)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(cookieValue(newCookie))))
                .andExpect(status().isOk());
        login(email, "abcd1234").andExpect(status().isUnauthorized());
        login(email, "newpass5678").andExpect(status().isOk());
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, 구글 회원은 409 PASSWORD_NOT_SET, 로그인 안 하면 401")
    void changePassword_rejected() throws Exception {
        String email = signedUp("abcd1234");
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        User googleMember = userRepository.save(User.join(newEmail(), "google"));
        trackUser(googleMember.getId());
        String body = "{\"currentPassword\":\"wrong1234\",\"newPassword\":\"newpass5678\"}";

        mockMvc.perform(patch(CHANGE).with(GatewayLogin.as(userId, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        mockMvc.perform(patch(CHANGE).with(GatewayLogin.as(googleMember.getId(), Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_NOT_SET"));
        mockMvc.perform(patch(CHANGE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("현재 비밀번호를 5번 연속 틀리면 맞는 비밀번호도 429 PASSWORD_CHANGE_LOCKED, 로그인은 그대로 된다")
    void changePassword_locked() throws Exception {
        String email = signedUp("abcd1234");
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        String changeKeyHash = sha256("password-change:" + userId);
        createdKeys.add("member:login-fail:" + changeKeyHash);
        createdKeys.add("member:login-lock:" + changeKeyHash);
        for (int i = 0; i < 5; i++) {
            changePassword(userId, "wrong1234", "newpass5678").andExpect(status().isBadRequest());
        }

        changePassword(userId, "abcd1234", "newpass5678")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_LOCKED"));
        cookieValue(login(email, "abcd1234").andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    private ResultActions changePassword(Long userId, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(patch(CHANGE).with(GatewayLogin.as(userId, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(currentPassword, newPassword)));
    }

    /** 이메일 인증(#557)을 마친 것으로 보고 그 이메일의 인증 토큰을 넣어 가입한다 */
    private ResultActions signUp(String email, String password, String nickname) throws Exception {
        return signUp(email, password, nickname, verifiedToken(email));
    }

    private ResultActions signUp(String email, String password, String nickname, String token) throws Exception {
        return mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\",\"emailVerificationToken\":\"%s\"}"
                        .formatted(email, password, nickname, token)));
    }

    /** 인증 코드 확인을 마친 것처럼 토큰을 발급한다 (코드 받기·확인은 EmailVerificationApiTest가 확인) */
    private String verifiedToken(String email) {
        String token = emailVerificationStore.issueToken(email.strip().toLowerCase(), EmailVerificationPurpose.SIGN_UP);
        createdKeys.add("member:email-verified:sign_up:" + sha256(token));
        return token;
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
