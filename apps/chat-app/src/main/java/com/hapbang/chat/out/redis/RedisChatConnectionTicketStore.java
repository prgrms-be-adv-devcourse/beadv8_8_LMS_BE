package com.hapbang.chat.out.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatConnectionTicketStore;
import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹소켓 연결 티켓을 Redis에 둔다(설계 문서 6장).
 * <ul>
 *     <li>키: {@link ChatRedisKeys#connectionTicket(String)}. 티켓 원문 대신 SHA-256(hex)을 쓴다. Redis를 읽을 수 있어도 키로 연결할 수
 *     없다.</li>
 *     <li>값: 회원 ID·역할·발급 시각·연결 만료(epoch ms) JSON. TTL은 티켓 유효 시간이다.</li>
 *     <li>사용: {@code GETDEL} 한 명령으로 꺼내고 지운다. 동시에 같은 티켓으로 두 번 연결해도 한쪽만 값을 받는다.</li>
 *     <li>발급 횟수: {@link ChatRedisKeys#connectionTicketRate(long)}에 {@code INCR}하고 창의 첫 발급만 {@code PEXPIRE}한다.
 *     둘을 Lua 스크립트 하나로 해 만료 없이 남는 키가 생기지 않는다.</li>
 * </ul>
 */
@Component
@ConditionalOnChatRealtime
@RequiredArgsConstructor
public class RedisChatConnectionTicketStore implements ChatConnectionTicketStore {

    private static final RedisScript<Long> ISSUE_COUNT = RedisLuaSupport.longLuaScript(
            "redis/scripts/ws-ticket-rate.lua");

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final ChatRedisKeys chatRedisKeys;

    @Override
    public void save(String ticket, ChatConnectionTicket value, Duration ttl) {
        StoredTicket stored = new StoredTicket(value.userId(), value.role().name(), value.issuedAt().toEpochMilli(),
                value.tokenExpiresAt().toEpochMilli());
        stringRedisTemplate.opsForValue().set(key(ticket), jsonMapper.writeValueAsString(stored), ttl);
    }

    @Override
    public Optional<ChatConnectionTicket> consume(String ticket) {
        String json = stringRedisTemplate.opsForValue().getAndDelete(key(ticket));
        if (json == null) {
            return Optional.empty();
        }
        StoredTicket stored = jsonMapper.readValue(json, StoredTicket.class);
        return Optional.of(new ChatConnectionTicket(stored.userId(), Role.valueOf(stored.role()),
                Instant.ofEpochMilli(stored.issuedAtMs()), Instant.ofEpochMilli(stored.tokenExpiresAtMs())));
    }

    @Override
    public long incrementIssueCount(Long userId, Duration window) {
        return RedisLuaSupport.runForLong(stringRedisTemplate, ISSUE_COUNT,
                List.of(chatRedisKeys.connectionTicketRate(userId)), window.toMillis());
    }

    private String key(String ticket) {
        return chatRedisKeys.connectionTicket(sha256Hex(ticket));
    }

    static String sha256Hex(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }

    /** Redis에 두는 모양 */
    record StoredTicket(Long userId, String role, long issuedAtMs, long tokenExpiresAtMs) {
    }
}
