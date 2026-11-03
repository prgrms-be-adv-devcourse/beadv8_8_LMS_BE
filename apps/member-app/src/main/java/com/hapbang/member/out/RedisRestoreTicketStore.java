package com.hapbang.member.out;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 복구 티켓을 Redis에 둔다 (#593). 키 {@code member:restore-ticket:{티켓 SHA-256}}, 값은 회원 ID, {@link #TTL} 뒤 저절로 사라진다.
 * 티켓 원문은 저장하지 않는다(로그인 실패 잠금 저장소와 같은 방식). 구글 로그인은 화면 이동이라 티켓을 이동 주소에 싣는데,
 * 짧게 살고 복구하면 지워져서 남은 주소로 다시 쓰기 어렵다(채팅 연결 티켓과 같은 판단)
 */
@Component
@RequiredArgsConstructor
public class RedisRestoreTicketStore implements RestoreTicketStore {

    static final String KEY_PREFIX = "member:restore-ticket:";
    /** 복구할까요? 화면에서 고르고 실패하면 다시 누를 시간 */
    static final Duration TTL = Duration.ofMinutes(10);
    private static final int TICKET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    @Override
    public String issue(Long userId) {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(KEY_PREFIX + hash(ticket), String.valueOf(userId), TTL);
        return ticket;
    }

    @Override
    public Optional<Long> find(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + hash(ticket))).map(Long::valueOf);
    }

    @Override
    public void delete(String ticket) {
        redisTemplate.delete(KEY_PREFIX + hash(ticket));
    }

    static String hash(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
