package com.hapbang.member.out;

import java.time.Instant;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.RefreshTokenProperties;

import lombok.RequiredArgsConstructor;

/**
 * refresh token을 Redis에 저장한다 (#370). 여러 명령을 한 번에 실행해야 하는 일은 Lua 스크립트로 원자적으로 처리한다.
 * <ul>
 *     <li>{@code member:refresh:{해시}}: 해시(hash) {userId, usedAt}. usedAt은 회전된 시각이고, 재사용 감지를 위해 남은 수명까지 둔다</li>
 *     <li>{@code member:user-refresh:{회원 ID}}: 그 회원의 아직 쓰지 않은 토큰 해시 집합 (한꺼번에 폐기할 때 쓴다)</li>
 * </ul>
 * 스크립트가 회원 집합 키를 안에서 만들므로 단일 Redis 기준이다 (Redis Cluster로 바꾸면 키 설계를 다시 본다)
 */
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String TOKEN_KEY_PREFIX = "member:refresh:";
    static final String USER_KEY_PREFIX = "member:user-refresh:";

    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'userId', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** 결과: "ROTATED:{회원 ID}", "REUSED:{회원 ID}", "NOT_FOUND" */
    private static final RedisScript<String> ROTATE = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            if not userId then
                return 'NOT_FOUND'
            end
            local usedAt = redis.call('HGET', KEYS[1], 'usedAt')
            if usedAt and (tonumber(ARGV[1]) - tonumber(usedAt)) > tonumber(ARGV[2]) then
                return 'REUSED:' .. userId
            end
            if not usedAt then
                redis.call('HSET', KEYS[1], 'usedAt', ARGV[1])
            end
            local userKey = ARGV[4] .. userId
            redis.call('SREM', userKey, ARGV[6])
            redis.call('HSET', KEYS[2], 'userId', userId)
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('SADD', userKey, ARGV[5])
            redis.call('PEXPIRE', userKey, ARGV[3])
            return 'ROTATED:' .. userId
            """, String.class);

    private static final RedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            redis.call('DEL', KEYS[1])
            if userId then
                redis.call('SREM', ARGV[1] .. userId, ARGV[2])
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> REVOKE_ALL = new DefaultRedisScript<>("""
            local hashes = redis.call('SMEMBERS', KEYS[1])
            for _, hash in ipairs(hashes) do
                redis.call('DEL', ARGV[1] .. hash)
            end
            redis.call('DEL', KEYS[1])
            return #hashes
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RefreshTokenProperties properties;

    @Override
    public void save(Long userId, String tokenHash) {
        redisTemplate.execute(SAVE, List.of(tokenKey(tokenHash), userKey(userId)),
                String.valueOf(userId), ttlMillis(), tokenHash);
    }

    @Override
    public RotationResult rotate(String oldTokenHash, String newTokenHash, Instant now) {
        String result = redisTemplate.execute(ROTATE, List.of(tokenKey(oldTokenHash), tokenKey(newTokenHash)),
                String.valueOf(now.toEpochMilli()), String.valueOf(properties.reuseGrace().toMillis()), ttlMillis(),
                USER_KEY_PREFIX, newTokenHash, oldTokenHash);
        if (result == null || result.equals("NOT_FOUND")) {
            return new RotationResult.NotFound();
        }
        Long userId = Long.valueOf(result.substring(result.indexOf(':') + 1));
        return result.startsWith("REUSED:") ? new RotationResult.Reused(userId) : new RotationResult.Rotated(userId);
    }

    @Override
    public void revoke(String tokenHash) {
        redisTemplate.execute(REVOKE, List.of(tokenKey(tokenHash)), USER_KEY_PREFIX, tokenHash);
    }

    @Override
    public void revokeAll(Long userId) {
        redisTemplate.execute(REVOKE_ALL, List.of(userKey(userId)), TOKEN_KEY_PREFIX);
    }

    private String ttlMillis() {
        return String.valueOf(properties.ttl().toMillis());
    }

    private static String tokenKey(String tokenHash) {
        return TOKEN_KEY_PREFIX + tokenHash;
    }

    private static String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }
}
