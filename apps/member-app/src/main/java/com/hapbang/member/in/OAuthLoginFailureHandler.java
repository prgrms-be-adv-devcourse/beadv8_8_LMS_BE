package com.hapbang.member.in;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 구글 로그인 실패 (#499): 프론트 화면으로 보내면서 실패 이유를 {@code error}로 붙인다.
 * 예: 이미 비밀번호로 가입된 이메일이면 {@code ?error=EMAIL_REGISTERED_WITH_PASSWORD}, 사용자가 동의를 취소하면 {@code ?error=access_denied}.
 * 브라우저 이동이라 JSON 오류 대신 화면으로 보낸다.
 * <p>
 * 복구할 수 있는 탈퇴 회원이면 {@code ?error=WITHDRAWN_RESTORABLE&restorableUntil=…}로 보내 프론트가
 * "복구할까요?"를 보여 주고 복구 API를 부르게 한다 (#593). 복구 티켓은 티켓 하나로 복구와 로그인이 되는 값이라 주소에 싣지 않고
 * 복구 티켓 쿠키({@link RestoreTicketCookies})로 내려준다. 주소는 방문 기록·Referer·분석 도구로 새어 나간다 (#633)
 */
@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    static final String UNKNOWN_ERROR = "LOGIN_FAILED";

    private final String loginFailureUrl;
    private final RestoreTicketCookies restoreTicketCookies;

    public OAuthLoginFailureHandler(@Value("${hapbang.auth.login-failure-url:/login}") String loginFailureUrl,
                                    RestoreTicketCookies restoreTicketCookies) {
        this.loginFailureUrl = loginFailureUrl;
        this.restoreTicketCookies = restoreTicketCookies;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String error = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : UNKNOWN_ERROR;
        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(loginFailureUrl).queryParam("error", error);
        if (exception instanceof RestorableOAuth2AuthenticationException restorable) {
            redirect.queryParam("restorableUntil", restorable.getRestorableUntil());
            response.addHeader(HttpHeaders.SET_COOKIE,
                    restoreTicketCookies.issue(restorable.getRestoreTicket()).toString());
        }
        response.sendRedirect(redirect.build().encode().toUriString());
    }
}
