package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import jakarta.servlet.http.Cookie;

/** 구글 로그인 진행 상태 Redis 저장소 (#370): 브라우저 쿠키로 묶고, 한 번만 꺼낼 수 있다 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisOAuth2AuthorizationRequestRepositoryTest {

    private static final String CALLBACK = "/api/v1/members/login/oauth2/code/google";

    @Autowired
    RedisOAuth2AuthorizationRequestRepository repository;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("로그인을 시작하면 진행 상태를 Redis에 3분 두고, 브라우저 ID만 Lax 쿠키로 준다")
    void save() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(authorizationRequest("state-1"), new MockHttpServletRequest(), response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(RedisOAuth2AuthorizationRequestRepository.COOKIE_NAME + "=")
                .contains("Path=/api/v1/members", "Max-Age=180", "HttpOnly", "Secure", "SameSite=Lax");
        String key = RedisOAuth2AuthorizationRequestRepository.KEY_PREFIX + track(browserId(setCookie));
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(170L, 180L);
    }

    @Test
    @DisplayName("콜백에서 같은 브라우저·같은 state면 진행 상태(PKCE 포함)를 돌려주고, 쿠키를 지운다")
    void remove() {
        OAuth2AuthorizationRequest saved = authorizationRequest("state-1");
        String browserId = save(saved);
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback(browserId, "state-1"), response);

        assertThat(removed).isNotNull();
        assertThat(removed.getState()).isEqualTo("state-1");
        assertThat(removed.getAttributes()).containsEntry("code_verifier", "verifier-1");
        assertThat(removed.getRedirectUri()).isEqualTo(saved.getRedirectUri());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("한 번 꺼낸 진행 상태는 다시 꺼낼 수 없다")
    void remove_onlyOnce() {
        String browserId = save(authorizationRequest("state-1"));
        repository.removeAuthorizationRequest(callback(browserId, "state-1"), new MockHttpServletResponse());

        assertThat(repository.removeAuthorizationRequest(callback(browserId, "state-1"), new MockHttpServletResponse()))
                .isNull();
    }

    @Test
    @DisplayName("콜백의 state가 저장한 state와 다르면 없는 것으로 본다")
    void remove_stateMismatch() {
        String browserId = save(authorizationRequest("state-1"));

        assertThat(repository.removeAuthorizationRequest(callback(browserId, "other-state"), new MockHttpServletResponse()))
                .isNull();
    }

    @Test
    @DisplayName("로그인을 시작한 쿠키가 없는 브라우저는 남이 만든 콜백 링크로 로그인할 수 없다 (로그인 CSRF)")
    void remove_withoutCookie() {
        save(authorizationRequest("state-1"));
        MockHttpServletRequest otherBrowser = new MockHttpServletRequest("GET", CALLBACK);
        otherBrowser.setParameter("state", "state-1");

        assertThat(repository.removeAuthorizationRequest(otherBrowser, new MockHttpServletResponse())).isNull();
    }

    @Test
    @DisplayName("형식이 맞지 않는 쿠키 값은 Redis를 찾지 않고 무시한다")
    void remove_invalidCookie() {
        MockHttpServletRequest request = callback("not-a-valid-id", "state-1");

        assertThat(repository.removeAuthorizationRequest(request, new MockHttpServletResponse())).isNull();
    }

    @Test
    @DisplayName("load는 꺼내기만 하고 지우지 않는다")
    void load_keeps() {
        String browserId = save(authorizationRequest("state-1"));

        assertThat(repository.loadAuthorizationRequest(callback(browserId, "state-1"))).isNotNull();
        assertThat(repository.loadAuthorizationRequest(callback(browserId, "state-1"))).isNotNull();
    }

    private String save(OAuth2AuthorizationRequest authorizationRequest) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorizationRequest, new MockHttpServletRequest(), response);
        return track(browserId(response.getHeader(HttpHeaders.SET_COOKIE)));
    }

    private static MockHttpServletRequest callback(String browserId, String state) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", CALLBACK);
        request.setCookies(new Cookie(RedisOAuth2AuthorizationRequestRepository.COOKIE_NAME, browserId));
        request.setParameter("state", state);
        return request;
    }

    private static OAuth2AuthorizationRequest authorizationRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-" + UUID.randomUUID())
                .redirectUri("http://localhost" + CALLBACK)
                .state(state)
                .attributes(Map.of("registration_id", "google", "code_verifier", "verifier-1"))
                .build();
    }

    private static String browserId(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private String track(String browserId) {
        createdKeys.add(RedisOAuth2AuthorizationRequestRepository.KEY_PREFIX + browserId);
        return browserId;
    }
}
