package com.hapbang.member.out;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.LoginLockProperties;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 확인 실패 횟수·잠금을 Redis에 둔다 (#499). Redis 키에는 받은 키(이메일 등) 원문 대신 SHA-256 해시를 쓴다.
 * <ul>
 *     <li>{@code member:login-fail:{해시}}: 실패 횟수. 첫 실패부터 잠금 시간 동안만 센다</li>
 *     <li>{@code member:login-lock:{해시}}: 잠금 표시. 잠금 시간이 지나면 저절로 사라진다</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(LoginLockProperties.class)
@RequiredArgsConstructor
public class RedisLoginAttemptStore implements LoginAttemptStore {

    static final String FAIL_KEY_PREFIX = "member:login-fail:";
    static final String LOCK_KEY_PREFIX = "member:login-lock:";

    /** 실패를 세고, 횟수에 이르면 잠금 표시를 남기고 횟수는 지운다. 결과: 1 = 이번에 잠김, 0 = 아직 */
    private static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if count >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginLockProperties properties;

    @Override
    public boolean isLocked(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_KEY_PREFIX + hash(key)));
    }

    @Override
    public boolean recordFailure(String key) {
        String hash = hash(key);
        Long locked = redisTemplate.execute(RECORD_FAILURE, List.of(FAIL_KEY_PREFIX + hash, LOCK_KEY_PREFIX + hash),
                String.valueOf(properties.maxFailures()), String.valueOf(properties.duration().toMillis()));
        return Long.valueOf(1).equals(locked);
    }

    @Override
    public void reset(String key) {
        redisTemplate.delete(FAIL_KEY_PREFIX + hash(key));
    }

    @Override
    public void unlock(String key) {
        String hash = hash(key);
        redisTemplate.delete(List.of(FAIL_KEY_PREFIX + hash, LOCK_KEY_PREFIX + hash));
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
