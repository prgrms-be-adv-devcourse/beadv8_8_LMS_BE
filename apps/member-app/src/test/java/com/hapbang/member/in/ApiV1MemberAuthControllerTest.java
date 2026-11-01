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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;

import jakarta.servlet.http.Cookie;

/** 인증 API (#370): refresh token 쿠키로 재발급·로그아웃. 실제 Redis와 보안 설정으로 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class ApiV1MemberAuthControllerTest {

    private static final String REFRESH = "/api/v1/members/auth/refresh";
    private static final String LOGOUT = "/api/v1/members/auth/logout";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RSAKey jwtSigningKey;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트가 만든 refresh token·회원의 Redis 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("refresh token 쿠키로 재발급하면 access token은 본문으로, 새 refresh token은 같은 속성의 쿠키로 준다 (CSRF 토큰 없이)")
    void refresh() throws Exception {
        String refreshToken = login(saveMember(Role.USER));

        MockHttpServletResponse response = mockMvc.perform(post(REFRESH).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andReturn().getResponse();

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(RefreshTokenCookies.NAME + "=")
                .contains("Path=/api/v1/members/auth", "Max-Age=1209600", "Secure", "HttpOnly", "SameSite=Strict");
        assertThat(cookieValue(setCookie)).isNotBlank().isNotEqualTo(refreshToken);
    }

    @Test
    @DisplayName("재발급으로 받은 새 refresh token으로 다시 재발급할 수 있다")
    void refresh_withRotatedToken() throws Exception {
        String rotated = refreshAndGetNewToken(login(saveMember(Role.USER)));

        mockMvc.perform(post(REFRESH).cookie(refreshCookie(rotated)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 탭이 방금 회전한 옛 refresh token으로 재발급하면 access token만 주고 쿠키는 바꾸지 않는다 (먼저 받은 새 토큰이 유효)")
    void refresh_withinGrace() throws Exception {
        String old = login(saveMember(Role.USER));
        String rotated = refreshAndGetNewToken(old);

        MockHttpServletResponse response = mockMvc.perform(post(REFRESH).cookie(refreshCookie(old)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(rotated)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("회전 뒤 로그아웃하면 유예 시간 안이라도 옛 refresh token으로 재발급할 수 없다")
    void refresh_withinGrace_afterLogout() throws Exception {
        String old = login(saveMember(Role.USER));
        String rotated = refreshAndGetNewToken(old);
        mockMvc.perform(post(LOGOUT).cookie(refreshCookie(rotated)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(REFRESH).cookie(refreshCookie(old)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("재발급한 access token에는 로그인 뒤 바뀐 지금 역할이 담긴다")
    void refresh_reflectsCurrentRole() throws Exception {
        User user = saveMember(Role.USER);
        String refreshToken = login(user);
        user.changeRole(Role.INFLUENCER);
        userRepository.saveAndFlush(user);

        String body = mockMvc.perform(post(REFRESH).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build()
                .decode(JsonPath.read(body, "$.data.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(user.getId()));
        assertThat(jwt.getClaims().get("role")).isEqualTo("INFLUENCER");
    }

    @Test
    @DisplayName("refresh token 쿠키가 없으면 401 INVALID_REFRESH_TOKEN")
    void refresh_withoutCookie() throws Exception {
        mockMvc.perform(post(REFRESH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("모르는 refresh token이면 401 INVALID_REFRESH_TOKEN")
    void refresh_unknownToken() throws Exception {
        mockMvc.perform(post(REFRESH).cookie(refreshCookie("unknown-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("로그아웃하면 204와 함께 쿠키를 지우고, 그 refresh token으로는 더 재발급할 수 없다")
    void logout() throws Exception {
        String refreshToken = login(saveMember(Role.USER));

        String setCookie = mockMvc.perform(post(LOGOUT).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).startsWith(RefreshTokenCookies.NAME + "=;").contains("Max-Age=0", "Path=/api/v1/members/auth");
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh token 쿠키 없이 로그아웃해도 204")
    void logout_withoutCookie() throws Exception {
        mockMvc.perform(post(LOGOUT))
                .andExpect(status().isNoContent());
    }

    private User saveMember(Role role) {
        User user = userRepository.save(MemberFixture.member(role));
        createdKeys.add("member:user-refresh:" + user.getId());
        return user;
    }

    private String login(User user) {
        return track(memberFacade.issueTokens(user.getId(), user.getRole()).refreshToken());
    }

    private String refreshAndGetNewToken(String refreshToken) throws Exception {
        String setCookie = mockMvc.perform(post(REFRESH).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return track(cookieValue(setCookie));
    }

    /** 응답으로 받은 refresh token의 Redis 키를 정리 대상에 넣는다 */
    private String track(String refreshToken) {
        createdKeys.add("member:refresh:" + sha256(refreshToken));
        return refreshToken;
    }

    private static Cookie refreshCookie(String value) {
        return new Cookie(RefreshTokenCookies.NAME, value);
    }

    private String cookieValue(String setCookie) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        return value.isEmpty() ? value : track(value);
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
