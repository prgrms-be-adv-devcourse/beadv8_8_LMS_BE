package com.hapbang.member.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.RefreshTokenProperties;

import lombok.RequiredArgsConstructor;

/**
 * refresh token을 Redis에 저장한다 (#370). 여러 명령을 한 번에 실행해야 하는 일은 Lua 스크립트로 원자적으로 처리한다.
 * <ul>
 *     <li>{@code member:refresh:{해시}}: 해시(hash) {userId, usedAt, next}. usedAt은 회전된 시각, next는 그때 받은 새 토큰 해시이고,
 *     재사용 감지를 위해 남은 수명까지 둔다</li>
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

    /**
     * 결과: "ROTATED:{회원 ID}", "GRACE:{회원 ID}", "REUSED:{회원 ID}", "NOT_FOUND".
     * 첫 회전 때 옛 토큰에 회전 시각(usedAt)과 새 토큰 해시(next)를 남긴다. 유예 안에 옛 토큰이 다시 오면 새 토큰을 만들지 않고,
     * next가 아직 살아 있고 쓰이지 않았을 때만 GRACE로 답한다. 그래서 유예 안 재회전으로 토큰이 갈라지지 않고,
     * 로그아웃·전부 폐기로 next가 지워졌거나 next가 이미 회전됐으면 거절된다
     */
    private static final RedisScript<String> ROTATE = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            if not userId then
                return 'NOT_FOUND'
            end
            local usedAt = redis.call('HGET', KEYS[1], 'usedAt')
            if usedAt then
                if (tonumber(ARGV[1]) - tonumber(usedAt)) > tonumber(ARGV[2]) then
                    return 'REUSED:' .. userId
                end
                local nextHash = redis.call('HGET', KEYS[1], 'next')
                if nextHash then
                    local nextKey = ARGV[7] .. nextHash
                    if redis.call('HEXISTS', nextKey, 'userId') == 1 and redis.call('HEXISTS', nextKey, 'usedAt') == 0 then
                        return 'GRACE:' .. userId
                    end
                end
                return 'NOT_FOUND'
            end
            redis.call('HSET', KEYS[1], 'usedAt', ARGV[1], 'next', ARGV[5])
            local userKey = ARGV[4] .. userId
            redis.call('SREM', userKey, ARGV[6])
            redis.call('HSET', KEYS[2], 'userId', userId)
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('SADD', userKey, ARGV[5])
            redis.call('PEXPIRE', userKey, ARGV[3])
            return 'ROTATED:' .. userId
            """, String.class);

    /** 결과: 폐기한 토큰의 회원 ID, 없던 토큰이면 nil */
    private static final RedisScript<String> REVOKE = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            redis.call('DEL', KEYS[1])
            if userId then
                redis.call('SREM', ARGV[1] .. userId, ARGV[2])
            end
            return userId
            """, String.class);

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
                USER_KEY_PREFIX, newTokenHash, oldTokenHash, TOKEN_KEY_PREFIX);
        if (result == null || result.equals("NOT_FOUND")) {
            return new RotationResult.NotFound();
        }
        Long userId = Long.valueOf(result.substring(result.indexOf(':') + 1));
        if (result.startsWith("REUSED:")) {
            return new RotationResult.Reused(userId);
        }
        return result.startsWith("GRACE:") ? new RotationResult.Grace(userId) : new RotationResult.Rotated(userId);
    }

    @Override
    public Optional<Long> revoke(String tokenHash) {
        String userId = redisTemplate.execute(REVOKE, List.of(tokenKey(tokenHash)), USER_KEY_PREFIX, tokenHash);
        return Optional.ofNullable(userId).map(Long::valueOf);
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
