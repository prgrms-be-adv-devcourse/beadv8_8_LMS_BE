package com.hapbang.member.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비밀번호 로그인·변경 잠금 설정 (hapbang.auth.login-lock.*, #499, #506).
 *
 * @param maxFailures       이 횟수만큼 연속으로 틀리면 잠근다. 로그인은 (이메일, IP)마다, 비밀번호 변경은 회원마다 센다
 * @param duration          잠그는 시간. 실패 횟수도 이 시간 동안만 센다 (영구 잠금은 하지 않는다)
 * @param emailMaxFailures  로그인: IP와 상관없이 한 이메일이 {@code emailWindow} 동안 이만큼 틀리면 그 이메일을 {@code duration} 동안 잠근다
 *                          (IP를 여러 개 쓰는 대입 방지, #506). 최근 로그인에 성공한 IP는 이 잠금을 받지 않는다
 * @param emailWindow       로그인: 이메일 전체 실패를 세는 기간 (첫 실패부터)
 * @param knownIpRetention  로그인: 로그인에 성공한 IP를 기억하는 기간. 그 IP에서는 이메일 전체 잠금을 받지 않는다
 */
@ConfigurationProperties("hapbang.auth.login-lock")
public record LoginLockProperties(@DefaultValue("5") int maxFailures, @DefaultValue("15m") Duration duration,
                                  @DefaultValue("20") int emailMaxFailures, @DefaultValue("1h") Duration emailWindow,
                                  @DefaultValue("30d") Duration knownIpRetention) {

    public LoginLockProperties {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("hapbang.auth.login-lock.max-failures는 1 이상이어야 합니다.");
        }
        // 이메일 전체 상한이 IP별 횟수보다 작으면 IP 하나로 이메일 전체를 잠글 수 있어, 남이 내 이메일을 잠그는 문제(#506)가 다시 생긴다
        if (emailMaxFailures < maxFailures) {
            throw new IllegalArgumentException(
                    "hapbang.auth.login-lock.email-max-failures는 max-failures 이상이어야 합니다.");
        }
        requirePositive(duration, "duration");
        requirePositive(emailWindow, "email-window");
        requirePositive(knownIpRetention, "known-ip-retention");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("hapbang.auth.login-lock." + name + "은 0보다 커야 합니다.");
        }
    }
}
