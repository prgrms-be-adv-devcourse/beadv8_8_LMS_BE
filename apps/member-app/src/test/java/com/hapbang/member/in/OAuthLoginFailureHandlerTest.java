package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuthLoginFailureHandlerTest {

    OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler("/login");

    @Test
    @DisplayName("구글 로그인 실패는 프론트 로그인 화면으로 보내면서 오류 코드를 error로 붙인다")
    void failure() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("EMAIL_REGISTERED_WITH_PASSWORD")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=EMAIL_REGISTERED_WITH_PASSWORD");
    }

    @Test
    @DisplayName("사용자가 구글 동의를 취소하면 구글 오류 코드(access_denied)를 그대로 붙인다")
    void failure_accessDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=access_denied");
    }

    @Test
    @DisplayName("OAuth 오류가 아닌 실패는 LOGIN_FAILED")
    void failure_other() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new BadCredentialsException("x"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=LOGIN_FAILED");
    }
}
