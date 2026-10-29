package com.hapbang.member.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * refresh token 설정 (hapbang.jwt.refresh-token.*, #370).
 *
 * @param ttl        refresh token 수명. 이 기간 동안 한 번도 재발급하지 않으면 다시 로그인한다
 * @param reuseGrace 회전된 토큰이 다시 와도 재사용(탈취)으로 보지 않는 시간. 여러 탭이 동시에 재발급할 때 로그아웃되지 않게 한다
 */
@ConfigurationProperties("hapbang.jwt.refresh-token")
public record RefreshTokenProperties(@DefaultValue("14d") Duration ttl, @DefaultValue("10s") Duration reuseGrace) {

    public RefreshTokenProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("hapbang.jwt.refresh-token.ttl은 0보다 커야 합니다.");
        }
        if (reuseGrace == null || reuseGrace.isNegative()) {
            throw new IllegalArgumentException("hapbang.jwt.refresh-token.reuse-grace는 0 이상이어야 합니다.");
        }
    }
}
