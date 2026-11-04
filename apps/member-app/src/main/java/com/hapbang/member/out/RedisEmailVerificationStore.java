package com.hapbang.member.out;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 가입 이메일 인증을 Redis에 둔다 (#557). 키에는 이메일·IP 원문 대신 SHA-256 해시를 쓴다({@link RedisLoginAttemptStore#hash}).
 * <ul>
 *     <li>{@code member:email-code:{해시}}: 인증 코드, {@link #CODE_TTL}</li>
 *     <li>{@code member:email-code-fail:{해시}}: 코드 틀린 횟수 (코드와 함께 사라진다)</li>
 *     <li>{@code member:email-code-cooldown:{해시}}: 재발송 간격 표시, {@link #RESEND_COOLDOWN}</li>
 *     <li>{@code member:email-code-ip:{해시}}: IP당 발송 수, 첫 발송부터 {@link #IP_WINDOW}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisEmailVerificationStore implements EmailVerificationStore {

    /** 인증 코드 유효 시간 */
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 같은 이메일 재발송 간격 */
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    /** IP당 발송 한도와 세는 기간 (#557 결정: 1시간 10통) */
    public static final int IP_LIMIT = 10;
    public static final Duration IP_WINDOW = Duration.ofHours(1);

    static final String CODE_KEY_PREFIX = "member:email-code:";
    static final String FAIL_KEY_PREFIX = "member:email-code-fail:";
    static final String COOLDOWN_KEY_PREFIX = "member:email-code-cooldown:";
    static final String IP_KEY_PREFIX = "member:email-code-ip:";

    /** 센 수를 하나 늘리고, 처음이면 기간을 건다. 결과: 늘린 뒤의 수 */
    private static final RedisScript<Long> COUNT_IN_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryStartResendCooldown(String email) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(COOLDOWN_KEY_PREFIX + RedisLoginAttemptStore.hash(email), "1", RESEND_COOLDOWN));
    }

    @Override
    public void clearResendCooldown(String email) {
        redisTemplate.delete(COOLDOWN_KEY_PREFIX + RedisLoginAttemptStore.hash(email));
    }

    @Override
    public boolean tryCountIp(String ip) {
        Long count = redisTemplate.execute(COUNT_IN_WINDOW, List.of(IP_KEY_PREFIX + RedisLoginAttemptStore.hash(ip)),
                String.valueOf(IP_WINDOW.toMillis()));
        return count != null && count <= IP_LIMIT;
    }

    @Override
    public void saveCode(String email, String code) {
        String hash = RedisLoginAttemptStore.hash(email);
        redisTemplate.delete(FAIL_KEY_PREFIX + hash);
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + hash, code, CODE_TTL);
    }

    @Override
    public void deleteCode(String email) {
        String hash = RedisLoginAttemptStore.hash(email);
        redisTemplate.delete(List.of(CODE_KEY_PREFIX + hash, FAIL_KEY_PREFIX + hash));
    }
}
