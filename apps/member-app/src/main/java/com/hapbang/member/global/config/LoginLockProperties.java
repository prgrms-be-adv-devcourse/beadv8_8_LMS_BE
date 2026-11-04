package com.hapbang.member.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비밀번호 로그인 잠금 설정 (hapbang.auth.login-lock.*, #499).
 *
 * @param maxFailures 이 횟수만큼 연속으로 틀리면 잠근다
 * @param duration    잠그는 시간. 실패 횟수도 이 시간 동안만 센다 (영구 잠금은 하지 않는다)
 */
@ConfigurationProperties("hapbang.auth.login-lock")
public record LoginLockProperties(@DefaultValue("5") int maxFailures, @DefaultValue("15m") Duration duration) {

    public LoginLockProperties {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("hapbang.auth.login-lock.max-failures는 1 이상이어야 합니다.");
        }
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("hapbang.auth.login-lock.duration은 0보다 커야 합니다.");
        }
    }
}
