package com.hapbang.member.in;

import java.time.Duration;

/**
 * access token 응답 (#370). 프론트는 메모리에 두고 Authorization: Bearer로 보낸다.
 * expiresIn(초)으로 만료 전에 미리 재발급할 수 있다
 */
public record AccessTokenResponse(String accessToken, long expiresIn) {

    public static AccessTokenResponse of(String accessToken, Duration ttl) {
        return new AccessTokenResponse(accessToken, ttl.toSeconds());
    }

    @Override
    public String toString() {
        // 로그 등에 토큰이 찍히지 않게 한다
        return "AccessTokenResponse[accessToken=****, expiresIn=" + expiresIn + "]";
    }
}
