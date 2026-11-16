package com.hapbang.member.out;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.domain.ClientIpKey;
import com.hapbang.member.global.config.LoginLockProperties;

import lombok.RequiredArgsConstructor;

/**
 * 비밀번호 로그인 실패 횟수·잠금을 Redis hash에 둔다 (#506). 이메일 하나의 상태를 키 하나에 모아, 비밀번호 재설정 때 IP와 상관없이 한 번에
 * 지울 수 있게 했다. 필드마다 TTL을 두므로({@code HPEXPIRE}) Redis 7.4 이상이 필요하다(운영 8.10, 테스트 {@code redis:8}).
 * 키·필드에는 이메일·IP 원문 대신 SHA-256 해시를 쓴다({@link RedisLoginAttemptStore#hash}).
 * <ul>
 *     <li>{@code member:login-attempt:{이메일 해시}}: 필드 {@code fail:{IP 해시}}(IP별 실패 수, 첫 실패부터 잠금 시간),
 *     {@code lock:{IP 해시}}(IP별 잠금), {@code fail:all}(이메일 전체 실패 수, 첫 실패부터 이메일 기간), {@code lock:all}(이메일 전체 잠금).
 *     필드가 모두 사라지면 키도 사라진다</li>
 *     <li>{@code member:login-known-ip:{이메일 해시}}: 필드 {@code {IP 해시}}(로그인에 성공한 IP, 마지막 성공부터 기억 기간)</li>
 * </ul>
 * 비밀번호 변경 잠금은 {@link RedisLoginAttemptStore}가 따로 센다.
 */
@Component
@EnableConfigurationProperties(LoginLockProperties.class)
@RequiredArgsConstructor
public class RedisPasswordLoginLockStore implements PasswordLoginLockStore {

    static final String ATTEMPT_KEY_PREFIX = "member:login-attempt:";
    static final String KNOWN_IP_KEY_PREFIX = "member:login-known-ip:";
    /** 공인 주소가 아니라 IP를 모를 때 쓰는 IP 자리 */
    static final String UNKNOWN_IP = "unknown";

    /** 결과: 0 = 받음, 1 = (이메일, IP) 잠금, 2 = 이메일 전체 잠금. ARGV[2]가 '1'이면 성공한 IP인지 본다 */
    private static final RedisScript<Long> CHECK = new DefaultRedisScript<>("""
            if redis.call('HEXISTS', KEYS[1], 'lock:' .. ARGV[1]) == 1 then
                return 1
            end
            if redis.call('HEXISTS', KEYS[1], 'lock:all') == 1 then
                if ARGV[2] == '1' and redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then
                    return 0
                end
                return 2
            end
            return 0
            """, Long.class);

    /**
     * IP별·이메일 전체 실패를 하나씩 센다. 횟수에 이르면 잠금 필드를 남기고 횟수 필드를 지운다.
     * 결과: 1 = 이번에 (이메일, IP)가 잠김, 2 = 이번에 이메일 전체가 잠김, 3 = 둘 다, 0 = 아직.
     * ARGV: IP 자리, IP별 횟수, 잠금 시간(ms), 이메일 전체 횟수, 이메일 기간(ms)
     */
    private static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local function count(field, ttl)
                local n = redis.call('HINCRBY', KEYS[1], field, 1)
                if n == 1 or redis.call('HPTTL', KEYS[1], 'FIELDS', 1, field)[1] < 0 then
                    redis.call('HPEXPIRE', KEYS[1], ttl, 'FIELDS', 1, field)
                end
                return n
            end
            local function lock(failField, lockField)
                redis.call('HSET', KEYS[1], lockField, '1')
                redis.call('HPEXPIRE', KEYS[1], ARGV[3], 'FIELDS', 1, lockField)
                redis.call('HDEL', KEYS[1], failField)
            end
            local result = 0
            if count('fail:' .. ARGV[1], ARGV[3]) >= tonumber(ARGV[2]) then
                lock('fail:' .. ARGV[1], 'lock:' .. ARGV[1])
                result = result + 1
            end
            if count('fail:all', ARGV[5]) >= tonumber(ARGV[4]) then
                lock('fail:all', 'lock:all')
                result = result + 2
            end
            return result
            """, Long.class);

    /** 이 IP의 실패 수를 지우고, ARGV[2]가 '1'이면 성공한 IP로 기억한다(기억 기간을 새로 건다) */
    private static final RedisScript<Long> RECORD_SUCCESS = new DefaultRedisScript<>("""
            redis.call('HDEL', KEYS[1], 'fail:' .. ARGV[1])
            if ARGV[2] == '1' then
                redis.call('HSET', KEYS[2], ARGV[1], '1')
                redis.call('HPEXPIRE', KEYS[2], ARGV[3], 'FIELDS', 1, ARGV[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginLockProperties properties;

    @Override
    public Lock check(String email, @Nullable ClientIpKey ipKey) {
        String emailHash = RedisLoginAttemptStore.hash(email);
        Long result = redisTemplate.execute(CHECK, List.of(ATTEMPT_KEY_PREFIX + emailHash, KNOWN_IP_KEY_PREFIX + emailHash),
                ipField(ipKey), ipKey != null ? "1" : "0");
        if (result == null || result == 0) {
            return Lock.NONE;
        }
        return result == 1 ? Lock.EMAIL_IP : Lock.EMAIL;
    }

    @Override
    public FailureResult recordFailure(String email, @Nullable ClientIpKey ipKey) {
        Long result = redisTemplate.execute(RECORD_FAILURE, List.of(attemptKey(email)),
                ipField(ipKey), String.valueOf(properties.maxFailures()), String.valueOf(properties.duration().toMillis()),
                String.valueOf(properties.emailMaxFailures()), String.valueOf(properties.emailWindow().toMillis()));
        long flags = result == null ? 0 : result;
        return new FailureResult((flags & 1) != 0, (flags & 2) != 0);
    }

    @Override
    public void recordSuccess(String email, @Nullable ClientIpKey ipKey) {
        String emailHash = RedisLoginAttemptStore.hash(email);
        redisTemplate.execute(RECORD_SUCCESS, List.of(ATTEMPT_KEY_PREFIX + emailHash, KNOWN_IP_KEY_PREFIX + emailHash),
                ipField(ipKey), ipKey != null ? "1" : "0", String.valueOf(properties.knownIpRetention().toMillis()));
    }

    @Override
    public void unlockAll(String email) {
        redisTemplate.delete(attemptKey(email));
    }

    static String attemptKey(String email) {
        return ATTEMPT_KEY_PREFIX + RedisLoginAttemptStore.hash(email);
    }

    static String ipField(@Nullable ClientIpKey ipKey) {
        return ipKey == null ? UNKNOWN_IP : RedisLoginAttemptStore.hash(ipKey.value());
    }
}
