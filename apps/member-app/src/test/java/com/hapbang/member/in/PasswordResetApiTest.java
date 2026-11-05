package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberEmailVerificationUseCase;
import com.hapbang.member.domain.EmailVerificationPurpose;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.EmailVerificationStore;
import com.hapbang.member.out.LoginAttemptStore;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.RefreshTokenStore;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;

/** 비밀번호 재설정 (#557): 재설정 코드 받기 → 확인 → 새 비밀번호, 받는 사람 규칙, 토큰 용도·한 번 쓰기, 잠금 해제·모든 기기 로그아웃 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class PasswordResetApiTest {

    private static final String EMAIL_CODES = "/api/v1/members/email-codes";
    private static final String PASSWORD_RESET = "/api/v1/members/password-reset";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    EmailVerificationStore emailVerificationStore;

    @Autowired
    LoginAttemptStore loginAttemptStore;

    @Autowired
    RefreshTokenStore refreshTokenStore;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("재설정 코드 받기 → 확인 → 새 비밀번호: 새 비밀번호로만 로그인되고, 로그인 잠금이 풀리며, 같은 토큰은 다시 못 쓴다")
    void resetFlow() throws Exception {
        String email = newEmail();
        User user = passwordMember(email, "oldpass1234");
        for (int i = 0; i < 5; i++) {
            loginAttemptStore.recordFailure(email);
        }

        sendCode(email, "PASSWORD_RESET").andExpect(status().isOk());
        String token = verify(email, savedCode(email, "password_reset"));
        reset(email, token, "newpass5678")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("비밀번호를 다시 설정했습니다. 새 비밀번호로 로그인해 주세요."));

        assertThat(loginAttemptStore.isLocked(email)).isFalse();
        login(email, "oldpass1234").andExpect(status().isUnauthorized());
        login(email, "newpass5678").andExpect(status().isOk());
        createdKeys.add("member:user-refresh:" + user.getId());
        reset(email, token, "another9012")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("재설정하면 그 회원의 refresh token이 모두 폐기된다(모든 기기 로그아웃)")
    void resetRevokesAllDevices() throws Exception {
        String email = newEmail();
        User user = passwordMember(email, "oldpass1234");
        createdKeys.add("member:user-refresh:" + user.getId());
        String tokenHash = "reset-test-" + UUID.randomUUID();
        refreshTokenStore.save(user.getId(), tokenHash);
        createdKeys.add("member:refresh:" + tokenHash);

        reset(email, resetToken(email), "newpass5678").andExpect(status().isOk());

        assertThat(refreshTokenStore.revoke(tokenHash)).isEmpty();
    }

    @Test
    @DisplayName("받는 사람 규칙: 비밀번호 회원만 코드를 받고, 구글 회원·없는 이메일은 코드 없이 같은 200이다")
    void whoGetsCode() throws Exception {
        String passwordEmail = newEmail();
        passwordMember(passwordEmail, "oldpass1234");
        String googleEmail = newEmail();
        userRepository.save(User.join(googleEmail, nickname()));
        String unknownEmail = newEmail();

        String passwordBody = sendCode(passwordEmail, "PASSWORD_RESET").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String googleBody = sendCode(googleEmail, "PASSWORD_RESET").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String unknownBody = sendCode(unknownEmail, "PASSWORD_RESET").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(googleBody).isEqualTo(passwordBody).isEqualTo(unknownBody);
        assertThat(savedCode(passwordEmail, "password_reset")).matches("\\d{6}");
        assertThat(savedCode(googleEmail, "password_reset")).isNull();
        assertThat(savedCode(unknownEmail, "password_reset")).isNull();
    }

    @Test
    @DisplayName("가입용 토큰·다른 이메일의 토큰으로는 재설정할 수 없다 (400 EMAIL_NOT_VERIFIED)")
    void wrongToken() throws Exception {
        String email = newEmail();
        passwordMember(email, "oldpass1234");
        String signUpToken = emailVerificationStore.issueToken(email, EmailVerificationPurpose.SIGN_UP);
        createdKeys.add("member:email-verified:sign_up:" + hash(signUpToken));

        reset(email, signUpToken, "newpass5678")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        reset(email, resetToken(newEmail()), "newpass5678")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("새 비밀번호 규칙을 어기면 400이고 토큰은 남아 다시 시도할 수 있다")
    void invalidNewPassword() throws Exception {
        String email = newEmail();
        passwordMember(email, "oldpass1234");
        String token = resetToken(email);

        reset(email, token, "short").andExpect(status().isBadRequest());
        reset(email, token, "newpass5678").andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰을 받은 뒤 탈퇴했으면 재설정할 수 없다 (정상 회원만, 400 EMAIL_NOT_VERIFIED)")
    void withdrawnMember() throws Exception {
        String email = newEmail();
        User user = passwordMember(email, "oldpass1234");
        String token = resetToken(email);
        user.withdraw(LocalDateTime.now());
        userRepository.save(user);

        reset(email, token, "newpass5678")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    private User passwordMember(String email, String password) {
        User user = userRepository.save(User.join(email, nickname()));
        passwordCredentialRepository.save(PasswordCredential.register(user, passwordEncoder.encode(password), LocalDateTime.now()));
        return user;
    }

    /** 재설정 코드 확인을 마친 것처럼 재설정용 토큰을 발급한다 */
    private String resetToken(String email) {
        String token = emailVerificationStore.issueToken(email, EmailVerificationPurpose.PASSWORD_RESET);
        createdKeys.add("member:email-verified:password_reset:" + hash(token));
        return token;
    }

    private ResultActions sendCode(String email, String purpose) throws Exception {
        String ip = "2001:db8:" + UUID.randomUUID().toString().substring(0, 4) + "::1";
        createdKeys.add("member:email-code-ip:" + hash(MemberEmailVerificationUseCase.ipLimitKey(ip)));
        return mockMvc.perform(post(EMAIL_CODES)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"purpose\":\"%s\"}".formatted(email, purpose)));
    }

    private String verify(String email, String code) throws Exception {
        String body = mockMvc.perform(post(EMAIL_CODES + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"purpose\":\"PASSWORD_RESET\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.emailVerificationToken");
        createdKeys.add("member:email-verified:password_reset:" + hash(token));
        return token;
    }

    private ResultActions reset(String email, String token, String newPassword) throws Exception {
        return mockMvc.perform(post(PASSWORD_RESET).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"emailVerificationToken\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(email, token, newPassword)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String savedCode(String email, String purpose) {
        return redisTemplate.opsForValue().get("member:email-code:" + purpose + ":" + hash(email));
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = hash(email);
        for (String purpose : new String[] {"sign_up", "password_reset"}) {
            createdKeys.add("member:email-code:" + purpose + ":" + hash);
            createdKeys.add("member:email-code-fail:" + purpose + ":" + hash);
        }
        createdKeys.add("member:email-code-cooldown:" + hash);
        createdKeys.add("member:email-code-daily:" + hash);
        createdKeys.add("member:login-fail:" + hash);
        createdKeys.add("member:login-lock:" + hash);
        return email;
    }

    private static String nickname() {
        return "r" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
