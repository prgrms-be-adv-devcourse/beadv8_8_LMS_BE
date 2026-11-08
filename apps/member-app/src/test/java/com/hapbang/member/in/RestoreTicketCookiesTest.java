package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class RestoreTicketCookiesTest {

    private final RestoreTicketCookies cookies = new RestoreTicketCookies();

    @Test
    @DisplayName("복구 티켓 쿠키는 JS가 못 읽고 HTTPS·같은 사이트·복구 API에만 붙으며 티켓 수명(10분)만큼 산다")
    void issue() {
        ResponseCookie cookie = cookies.issue("ticket-1");

        assertThat(cookie.getName()).isEqualTo("RESTORE_TICKET");
        assertThat(cookie.getValue()).isEqualTo("ticket-1");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/members/restore");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("지우는 쿠키는 같은 이름·경로에 값이 비고 바로 만료된다")
    void expire() {
        ResponseCookie cookie = cookies.expire();

        assertThat(cookie.getName()).isEqualTo("RESTORE_TICKET");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/members/restore");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }
}
