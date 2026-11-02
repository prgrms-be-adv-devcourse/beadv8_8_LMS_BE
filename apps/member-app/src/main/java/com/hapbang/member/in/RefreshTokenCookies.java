package com.hapbang.member.in;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.RefreshTokenProperties;

import lombok.RequiredArgsConstructor;

/**
 * refresh token 쿠키 (#370). JS가 읽지 못하고(HttpOnly), HTTPS로만 오가고(Secure),
 * 다른 사이트에서 시작한 요청에는 붙지 않고(SameSite=Strict), 재발급·로그아웃 경로에만 붙는다(Path).
 * 로컬 http://localhost에서도 브라우저가 Secure 쿠키를 받는다
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    public static final String NAME = "REFRESH_TOKEN";
    static final String PATH = "/api/v1/members/auth";

    private final RefreshTokenProperties properties;

    /** 새 refresh token을 담은 쿠키 (refresh token 수명만큼 유지) */
    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(properties.ttl()).build();
    }

    /** 브라우저의 refresh token 쿠키를 지우는 쿠키 (로그아웃) */
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
