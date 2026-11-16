package com.hapbang.member.out;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.LoginRateLimitProperties;

import lombok.RequiredArgsConstructor;

/**
 * 로그인 요청 수를 IP마다 Redis에서 센다 (#506). 앱 메모리가 아니라 Redis로 세는 이유는 {@code api-gateway.md} rate limit의 회원 앱 예외
 * (IP마다 키가 끝없이 늘어나는데 TTL로 저절로 지워지고, 로그인은 이미 잠금 확인으로 Redis를 쓴다)다.
 * <ul>
 *     <li>{@code member:login-ip-rate:{해시}}: 첫 요청부터 기간 동안의 요청 수. 키에는 IP 원문 대신 SHA-256 해시를 쓴다</li>
 * </ul>
 * 고정 창이라 창 경계에 몰아 보내면 짧은 시간에 최대 2배까지 받는다(이메일 인증 제한과 같은 방식).
 */
@Component
@EnableConfigurationProperties(LoginRateLimitProperties.class)
@RequiredArgsConstructor
public class RedisLoginRateLimitStore implements LoginRateLimitStore {

    static final String KEY_PREFIX = "member:login-ip-rate:";

    /** 하나 세고, 처음이면 기간을 건다. 한도 안이면 0, 넘었으면 남은 밀리초(1 이상) */
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if count <= tonumber(ARGV[1]) then
                return 0
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                ttl = tonumber(ARGV[2])
            end
            return ttl
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginRateLimitProperties properties;

    @Override
    public long tryAcquire(String ipKey) {
        Long result = redisTemplate.execute(ACQUIRE, List.of(KEY_PREFIX + RedisLoginAttemptStore.hash(ipKey)),
                String.valueOf(properties.limit()), String.valueOf(properties.period().toMillis()));
        return result == null ? 0 : result;
    }
}
