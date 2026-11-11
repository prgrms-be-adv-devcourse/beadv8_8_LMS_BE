package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.PasswordCredential;
import com.hapbang.member.domain.User;
import com.hapbang.member.in.RefreshTokenCookies;
import com.hapbang.member.out.PasswordCredentialRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;

import jakarta.servlet.http.Cookie;

/** 역할별 초기 계정 (#520): 실제 DB·Redis로 만들고 비밀번호 로그인까지 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class MemberSeedAccountIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordCredentialRepository passwordCredentialRepository;

    @Autowired
    MockMvc mockMvc;

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
    @DisplayName("인플루언서·인증된 인플루언서·관리자 계정을 하나씩 만들고, 각 계정으로 비밀번호 로그인하면 그 역할의 토큰을 받는다")
    void createAll() throws Exception {
        memberFacade.createSeedAccounts();

        assertThat(userRepository.count()).isEqualTo(3);
        for (MemberSeedAccountUseCase.SeedAccount account : MemberSeedAccountUseCase.ACCOUNTS) {
            User user = userRepository.findByEmail(account.email()).orElseThrow();
            assertThat(user.getRole()).isEqualTo(account.role());
            assertThat(accessTokenRole(account.email(), user.getId())).isEqualTo(account.role().name());
        }
        assertThat(MemberSeedAccountUseCase.ACCOUNTS).extracting(MemberSeedAccountUseCase.SeedAccount::role)
                .containsExactlyInAnyOrder(Role.INFLUENCER, Role.VERIFIED_INFLUENCER, Role.ADMIN);
    }

    @Test
    @DisplayName("다시 실행해도 중복으로 만들지 않고, 바꾼 비밀번호를 되돌리지 않는다")
    void createAll_idempotent() {
        memberFacade.createSeedAccounts();
        User admin = userRepository.findByEmail("admin@hapbang.local").orElseThrow();
        PasswordCredential credential = passwordCredentialRepository.findByUserId(admin.getId()).orElseThrow();
        String changedHash = "{bcrypt}changed";
        credential.changePassword(changedHash, credential.getPasswordChangedAt());
        passwordCredentialRepository.saveAndFlush(credential);

        memberFacade.createSeedAccounts();

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(passwordCredentialRepository.findByUserId(admin.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(changedHash);
    }

    @Test
    @DisplayName("다른 회원이 초기 계정 닉네임(대소문자 무시)을 쓰고 있으면 그 계정만 건너뛰고 나머지는 만든다 (#646)")
    void createAll_nicknameTaken() {
        userRepository.save(User.join("someone@example.com", "관리자"));

        memberFacade.createSeedAccounts();

        assertThat(userRepository.findByEmail("admin@hapbang.local")).isEmpty();
        assertThat(userRepository.findByEmail("influencer@hapbang.local")).isPresent();
        assertThat(userRepository.findByEmail("verified-influencer@hapbang.local")).isPresent();
    }

    /** 비밀번호 로그인 → 재발급으로 받은 access token의 role */
    private String accessTokenRole(String email, Long userId) throws Exception {
        createdKeys.add("member:user-refresh:" + userId);
        String setCookie = mockMvc.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email,
                                MemberSeedAccountUseCase.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String refreshToken = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        createdKeys.add("member:refresh:" + sha256(refreshToken));
        String body = mockMvc.perform(post("/api/v1/members/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookies.NAME, refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build()
                .decode(JsonPath.read(body, "$.data.accessToken")).getClaimAsString("role");
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
