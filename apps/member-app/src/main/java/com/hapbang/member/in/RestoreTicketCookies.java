package com.hapbang.member.in;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.hapbang.member.out.RestoreTicketStore;

/**
 * 복구 티켓 쿠키 (#633). 구글 로그인은 화면 이동이라 응답 본문을 쓸 수 없어, 복구 티켓을 화면 주소 대신 이 쿠키로 넘긴다.
 * 주소에 실으면 방문 기록·Referer·분석 도구로 새어 나가고, 티켓 하나로 복구와 로그인이 되기 때문이다.
 * refresh token 쿠키({@link RefreshTokenCookies})와 같이 JS가 읽지 못하고(HttpOnly), HTTPS로만 오가고(Secure),
 * 다른 사이트에서 시작한 요청에는 붙지 않고(SameSite=Strict), 복구 API에만 붙는다(Path). 수명은 티켓과 같다
 */
@Component
public class RestoreTicketCookies {

    public static final String NAME = "RESTORE_TICKET";
    static final String PATH = "/api/v1/members/restore";

    /** 복구 티켓을 담은 쿠키 (티켓 수명만큼 유지) */
    public ResponseCookie issue(String restoreTicket) {
        return base(restoreTicket).maxAge(RestoreTicketStore.TTL).build();
    }

    /** 브라우저의 복구 티켓 쿠키를 지우는 쿠키 (복구에 성공했을 때) */
    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
