package com.hapbang.member.in;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.app.TokenPair;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 구글 로그인 성공 (#370): refresh token을 쿠키로 주고 프론트로 보낸다.
 * 콜백 응답은 302라 access token을 본문으로 줄 수 없다. 프론트가 시작할 때 재발급 API로 access token을 받는다
 */
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookies refreshTokenCookies;
    private final String loginSuccessUrl;

    public OAuthLoginSuccessHandler(MemberFacade memberFacade, RefreshTokenCookies refreshTokenCookies,
                                    @Value("${hapbang.auth.login-success-url:/}") String loginSuccessUrl) {
        this.memberFacade = memberFacade;
        this.refreshTokenCookies = refreshTokenCookies;
        this.loginSuccessUrl = loginSuccessUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // 회원 처리는 MemberOAuth2UserService가 끝냈다 (회원 ID·역할을 담은 MemberOAuth2User)
        if (!(authentication.getPrincipal() instanceof MemberOAuth2User member)) {
            throw new IllegalStateException("구글 로그인 결과가 회원 로그인 정보가 아닙니다.");
        }
        TokenPair tokens = memberFacade.issueTokens(member.getUserId(), member.getRole());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookies.issue(tokens.refreshToken()).toString());
        response.sendRedirect(loginSuccessUrl);
    }
}
