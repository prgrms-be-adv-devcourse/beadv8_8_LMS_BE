package com.hapbang.member.in;

import java.time.LocalDateTime;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.hapbang.member.domain.WithdrawnRestorableException;

/**
 * 구글 로그인한 회원이 복구할 수 있는 탈퇴 회원이다 (#593). 실패 처리({@link OAuthLoginFailureHandler})가 복구 티켓은 쿠키로,
 * 기한은 이동 주소로 넘길 수 있게 담아 둔다 (구글 로그인은 화면 이동이라 응답 본문을 쓸 수 없다, #633)
 */
class RestorableOAuth2AuthenticationException extends OAuth2AuthenticationException {

    private final String restoreTicket;
    private final LocalDateTime restorableUntil;

    RestorableOAuth2AuthenticationException(WithdrawnRestorableException cause) {
        super(new OAuth2Error(cause.getErrorCode().getCode(), cause.getMessage(), null), cause.getMessage(), cause);
        this.restoreTicket = cause.getRestoreTicket();
        this.restorableUntil = cause.getRestorableUntil();
    }

    String getRestoreTicket() {
        return restoreTicket;
    }

    LocalDateTime getRestorableUntil() {
        return restorableUntil;
    }
}
