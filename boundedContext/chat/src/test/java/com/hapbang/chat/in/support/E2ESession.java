package com.hapbang.chat.in.support;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.shared.member.domain.Role;

/**
 * 실제 서버에 로그인한 세션. 세션 쿠키와 CSRF 토큰을 담는다.
 * <p>
 * 프론트와 같은 방식으로 요청한다: 모든 요청에 쿠키를 싣고, 변경 요청(POST·DELETE)에는 {@code X-XSRF-TOKEN} 헤더를 붙인다.
 */
public record E2ESession(String sessionCookie, String xsrfToken) {

    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    /**
     * 테스트 전용 로그인 경로로 세션을 만들고, 그 세션으로 GET 요청을 보내 CSRF 토큰 쿠키를 받는다.
     */
    public static E2ESession login(RestTestClient client, Long userId, Role role) {
        List<String> loginCookies = client.post()
                .uri(E2ELoginConfig.LOGIN_PATH + "?userId={userId}&role={role}", userId, role)
                .exchange()
                .expectStatus().isNoContent()
                .returnResult().getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        String sessionCookie = cookie(loginCookies, "JSESSIONID");

        List<String> csrfCookies = client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, sessionCookie)
                .exchange()
                .expectStatus().isOk()
                .returnResult().getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        String xsrfToken = cookie(csrfCookies, XSRF_COOKIE).substring(XSRF_COOKIE.length() + 1);
        return new E2ESession(sessionCookie, xsrfToken);
    }

    /** 조회 요청용 쿠키 헤더 값 */
    public String cookieHeader() {
        return sessionCookie + "; " + XSRF_COOKIE + "=" + xsrfToken;
    }

    /** 변경 요청(POST·DELETE)에 쿠키와 CSRF 헤더를 붙인다 */
    public void applyTo(HttpHeaders headers) {
        headers.add(HttpHeaders.COOKIE, cookieHeader());
        headers.add(XSRF_HEADER, xsrfToken);
    }

    private static String cookie(List<String> setCookies, String name) {
        assertThat(setCookies).as(name + " 쿠키").isNotNull();
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .map(cookie -> cookie.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " 쿠키가 없습니다: " + setCookies));
    }
}
