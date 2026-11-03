package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.TokenPair;
import com.hapbang.member.global.config.RefreshTokenProperties;
import com.hapbang.shared.member.domain.Role;

class OAuthLoginSuccessHandlerTest {

    MemberFacade memberFacade = mock(MemberFacade.class);
    OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(memberFacade,
            new RefreshTokenCookies(new RefreshTokenProperties(Duration.ofDays(14), Duration.ofSeconds(10))),
            new RestoreTicketCookies(), "/");

    @Test
    @DisplayName("구글 로그인에 성공하면 그 회원의 refresh token을 쿠키로 주고 프론트로 보낸다")
    void success() throws Exception {
        given(memberFacade.issueTokens(42L, Role.INFLUENCER)).willReturn(new TokenPair("access", "refresh-value"));
        MemberOAuth2User member = new MemberOAuth2User(42L, Role.INFLUENCER, Map.of());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationToken(member, member.getAuthorities(), "google"));

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(RefreshTokenCookies.NAME + "=refresh-value;")
                .contains("Path=/api/v1/members/auth", "HttpOnly", "Secure", "SameSite=Strict");
        // 앞사람이 남긴 복구 티켓 쿠키는 지운다 (공용 PC, #633)
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> assertThat(cookie)
                .startsWith(RestoreTicketCookies.NAME + "=;")
                .contains("Max-Age=0", "Path=/api/v1/members/restore"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        // access token은 302 응답에 싣지 않는다 (프론트가 재발급 API로 받는다)
        assertThat(response.getContentAsString()).doesNotContain("access");
    }

    @Test
    @DisplayName("회원 로그인 정보가 아닌 인증이면 토큰을 발급하지 않는다")
    void notMember() {
        assertThatThrownBy(() -> handler.onAuthenticationSuccess(new MockHttpServletRequest(),
                new MockHttpServletResponse(), new TestingAuthenticationToken("someone", null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(memberFacade);
    }
}
