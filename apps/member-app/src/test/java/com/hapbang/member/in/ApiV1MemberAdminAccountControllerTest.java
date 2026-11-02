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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;

import jakarta.servlet.http.Cookie;

/** 관리자 계정 생성 API (#517). 실제 DB·Redis와 보안 설정으로 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class ApiV1MemberAdminAccountControllerTest {

    private static final String CREATE = "/api/v1/members/admin/accounts";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RSAKey jwtSigningKey;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트가 만든 Redis 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("관리자가 만든 계정은 역할이 관리자이고, 비밀번호 로그인하면 관리자 토큰을 받는다")
    void create() throws Exception {
        String email = newEmail();

        create(GatewayLogin.as(1L, Role.ADMIN), email)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        User created = userRepository.findByEmail(email).orElseThrow();
        createdKeys.add("member:user-refresh:" + created.getId());
        assertThat(created.getRole()).isEqualTo(Role.ADMIN);

        String setCookie = mockMvc.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"admin1234\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String refreshToken = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        createdKeys.add("member:refresh:" + sha256(refreshToken));
        String body = mockMvc.perform(post("/api/v1/members/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookies.NAME, refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        createdKeys.add("member:refresh:" + sha256(refreshToken));

        Jwt jwt = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build()
                .decode(JsonPath.read(body, "$.data.accessToken"));
        assertThat(jwt.getClaims().get("role")).isEqualTo("ADMIN");
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(created.getId()));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인하지 않으면 401이고 계정을 만들지 않는다")
    void create_forbidden() throws Exception {
        String email = newEmail();

        create(GatewayLogin.as(1L, Role.VERIFIED_INFLUENCER), email).andExpect(status().isForbidden());
        create(null, email).andExpect(status().isUnauthorized());

        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 DUPLICATE_EMAIL, 비밀번호 규칙을 어기면 400 (가입과 같은 규칙)")
    void create_invalid() throws Exception {
        String email = newEmail();
        User existing = userRepository.save(User.join(email, "existing"));
        createdKeys.add("member:user-refresh:" + existing.getId());

        create(GatewayLogin.as(1L, Role.ADMIN), email)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        mockMvc.perform(post(CREATE).with(GatewayLogin.as(1L, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"short\",\"nickname\":\"관리자\"}".formatted(newEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_LENGTH"));
    }

    private ResultActions create(RequestPostProcessor login, String email) throws Exception {
        var request = post(CREATE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"admin1234\",\"nickname\":\"관리자\"}".formatted(email));
        return mockMvc.perform(login == null ? request : request.with(login));
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = sha256(email);
        createdKeys.add("member:login-fail:" + hash);
        createdKeys.add("member:login-lock:" + hash);
        return email;
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
