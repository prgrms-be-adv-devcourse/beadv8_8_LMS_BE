package com.hapbang.member.out;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.member.domain.EmailVerificationPolicy;

import lombok.RequiredArgsConstructor;

/**
 * 가입 이메일 인증을 Redis에 둔다 (#557). 키에는 이메일·IP 원문 대신 SHA-256 해시를 쓴다({@link RedisLoginAttemptStore#hash}).
 * <ul>
 *     <li>{@code member:email-code:{해시}}: 인증 코드, {@link EmailVerificationPolicy#CODE_TTL}</li>
 *     <li>{@code member:email-code-fail:{해시}}: 코드 틀린 횟수 (코드와 함께 사라진다)</li>
 *     <li>{@code member:email-code-cooldown:{해시}}: 재발송 간격 표시, {@link EmailVerificationPolicy#RESEND_COOLDOWN}</li>
 *     <li>{@code member:email-code-ip:{해시}}: IP당 발송 수, 첫 발송부터 {@link EmailVerificationPolicy#IP_WINDOW}</li>
 *     <li>{@code member:email-verified:{토큰 해시}}: 인증 토큰 → 이메일, {@link EmailVerificationPolicy#TOKEN_TTL}. 토큰 원문은 저장하지 않는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisEmailVerificationStore implements EmailVerificationStore {

    static final String CODE_KEY_PREFIX = "member:email-code:";
    static final String FAIL_KEY_PREFIX = "member:email-code-fail:";
    static final String COOLDOWN_KEY_PREFIX = "member:email-code-cooldown:";
    static final String IP_KEY_PREFIX = "member:email-code-ip:";
    static final String TOKEN_KEY_PREFIX = "member:email-verified:";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 코드 확인. 결과: 1 = 맞음(코드·틀린 횟수 지움), 2 = 틀림, 3 = 정한 횟수만큼 틀려 코드를 지움, 0 = 코드 없음.
     * 틀린 횟수는 코드와 같은 때 사라지게 코드의 남은 시간을 그대로 건다
     */
    private static final RedisScript<Long> CHECK_CODE = new DefaultRedisScript<>("""
            local saved = redis.call('GET', KEYS[1])
            if not saved then
                return 0
            end
            if saved == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end
            local count = redis.call('INCR', KEYS[2])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl > 0 then
                redis.call('PEXPIRE', KEYS[2], ttl)
            end
            if count >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 3
            end
            return 2
            """, Long.class);

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
                .setIfAbsent(COOLDOWN_KEY_PREFIX + RedisLoginAttemptStore.hash(email), "1", EmailVerificationPolicy.RESEND_COOLDOWN));
    }

    @Override
    public void clearResendCooldown(String email) {
        redisTemplate.delete(COOLDOWN_KEY_PREFIX + RedisLoginAttemptStore.hash(email));
    }

    @Override
    public boolean tryCountIp(String ip) {
        Long count = redisTemplate.execute(COUNT_IN_WINDOW, List.of(IP_KEY_PREFIX + RedisLoginAttemptStore.hash(ip)),
                String.valueOf(EmailVerificationPolicy.IP_WINDOW.toMillis()));
        return count != null && count <= EmailVerificationPolicy.IP_LIMIT;
    }

    @Override
    public void saveCode(String email, String code) {
        String hash = RedisLoginAttemptStore.hash(email);
        redisTemplate.delete(FAIL_KEY_PREFIX + hash);
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + hash, code, EmailVerificationPolicy.CODE_TTL);
    }

    @Override
    public void deleteCode(String email) {
        String hash = RedisLoginAttemptStore.hash(email);
        redisTemplate.delete(List.of(CODE_KEY_PREFIX + hash, FAIL_KEY_PREFIX + hash));
    }

    @Override
    public CodeCheck checkCode(String email, String code, int maxAttempts) {
        String hash = RedisLoginAttemptStore.hash(email);
        Long result = redisTemplate.execute(CHECK_CODE, List.of(CODE_KEY_PREFIX + hash, FAIL_KEY_PREFIX + hash),
                code, String.valueOf(maxAttempts));
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> CodeCheck.MATCHED;
            case 2 -> CodeCheck.WRONG;
            case 3 -> CodeCheck.ATTEMPTS_EXCEEDED;
            default -> CodeCheck.NOT_FOUND;
        };
    }

    @Override
    public String issueToken(String email) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + RedisLoginAttemptStore.hash(token), email, EmailVerificationPolicy.TOKEN_TTL);
        return token;
    }

    @Override
    public Optional<String> findTokenEmail(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + RedisLoginAttemptStore.hash(token)));
    }

    @Override
    public void deleteToken(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + RedisLoginAttemptStore.hash(token));
        }
    }
}
