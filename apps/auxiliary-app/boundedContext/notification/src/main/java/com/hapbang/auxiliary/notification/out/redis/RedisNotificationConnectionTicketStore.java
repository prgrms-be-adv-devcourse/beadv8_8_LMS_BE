package com.hapbang.auxiliary.notification.out.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.app.NotificationConnectionTicketStore;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.shared.member.domain.Role;

import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 연결 티켓을 Redis에 둔다(채팅 {@code RedisChatConnectionTicketStore} 복사, ND-028).
 * <ul>
 *     <li>키: 티켓 원문 대신 SHA-256(hex). Redis를 읽을 수 있어도 키로 연결할 수 없다.</li>
 *     <li>값: 회원 ID·역할·발급 시각·연결 만료(epoch ms) JSON. TTL은 티켓 유효 시간이다(연결 만료와 다르다).</li>
 *     <li>사용: {@code GETDEL} 한 명령으로 꺼내고 지운다. 같은 티켓으로 동시에 연결해도 한쪽만 값을 받는다.</li>
 *     <li>발급 횟수: INCR과 창의 첫 PEXPIRE를 Lua 스크립트 하나로 한다.</li>
 * </ul>
 */
@Component
@ConditionalOnNotificationRealtime
public class RedisNotificationConnectionTicketStore implements NotificationConnectionTicketStore {

    private final RedisScript<Long> issueCount;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final NotificationRedisKeys keys;

    public RedisNotificationConnectionTicketStore(StringRedisTemplate redis, JsonMapper jsonMapper,
            NotificationRedisKeys keys) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.keys = keys;
        this.issueCount = RedisScript.of(new ClassPathResource("notification/redis/ws-ticket-rate.lua"), Long.class);
        // 파일을 지금 읽는다. 없으면 첫 발급이 아니라 기동 때 실패한다
        this.issueCount.getSha1();
    }

    @Override
    public void save(String ticket, ConnectionTicket value, Duration ttl) {
        StoredTicket stored = new StoredTicket(value.userId(), value.role().name(), value.issuedAt().toEpochMilli(),
                value.connectionExpiresAt().toEpochMilli());
        redis.opsForValue().set(key(ticket), jsonMapper.writeValueAsString(stored), ttl);
    }

    @Override
    public Optional<ConnectionTicket> consume(String ticket) {
        String json = redis.opsForValue().getAndDelete(key(ticket));
        if (json == null) {
            return Optional.empty();
        }
        StoredTicket stored = jsonMapper.readValue(json, StoredTicket.class);
        return Optional.of(new ConnectionTicket(stored.userId(), Role.valueOf(stored.role()),
                Instant.ofEpochMilli(stored.issuedAtMs()), Instant.ofEpochMilli(stored.connectionExpiresAtMs())));
    }

    @Override
    public long incrementIssueCount(Long userId, Duration window) {
        Long count = redis.execute(issueCount, List.of(keys.connectionTicketRate(userId)),
                String.valueOf(window.toMillis()));
        if (count == null) {
            throw new IllegalStateException("발급 횟수 Lua 결과가 없습니다.");
        }
        return count;
    }

    private String key(String ticket) {
        return keys.connectionTicket(sha256Hex(ticket));
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
    record StoredTicket(Long userId, String role, long issuedAtMs, long connectionExpiresAtMs) {
    }
}
