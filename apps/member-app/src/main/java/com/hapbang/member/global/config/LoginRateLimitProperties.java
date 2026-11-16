package com.hapbang.member.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비밀번호 로그인 IP 한도 설정 (hapbang.auth.login-rate-limit.*, #506).
 *
 * @param limit  한 IP가 기간 안에 보낼 수 있는 로그인 요청 수 (성공·실패 모두 센다)
 * @param period 세는 기간. 첫 요청부터 이 시간이 지나면 0부터 다시 센다
 */
@ConfigurationProperties("hapbang.auth.login-rate-limit")
public record LoginRateLimitProperties(@DefaultValue("10") int limit, @DefaultValue("1m") Duration period) {

    public LoginRateLimitProperties {
        if (limit < 1) {
            throw new IllegalArgumentException("hapbang.auth.login-rate-limit.limit는 1 이상이어야 합니다.");
        }
        if (period == null || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("hapbang.auth.login-rate-limit.period는 0보다 커야 합니다.");
        }
    }
}
