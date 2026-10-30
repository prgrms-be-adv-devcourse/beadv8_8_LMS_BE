package com.hapbang.chat.out.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatPresence;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

import lombok.extern.slf4j.Slf4j;

/**
 * 접속 중인 회원을 Redis에 둔다(설계 Q21).
 * <ul>
 *     <li>{@link ChatRedisKeys#presenceOnline()}: 원소 회원 ID, 점수 만료 시각(ms). 점수가 지금보다 크면 접속 중이다.</li>
 *     <li>{@link ChatRedisKeys#presenceUser(long)}: 원소 {@code 앱ID:세션ID}, 점수 만료 시각(ms). 키에도 만료를 건다.
 *     살아 있는 연결이 있으면 키가 만료되지 않으므로, 연결과 하트비트 때 점수가 지난 원소를 지운다.</li>
 *     <li>연결: 회원 연결에 넣고 접속 목록에 {@code ZADD GT}로 만료를 넣는다(다른 앱이 더 늦은 만료를 넣었으면 줄이지 않는다).</li>
 *     <li>끊김: 회원 연결에서 빼고, 살아 있는 연결이 없으면 접속 목록에서도 뺀다. 확인과 삭제를 Lua 스크립트 하나로 한다.</li>
 *     <li>앱 ID는 시작할 때마다 새로 만든다. 재시작 전 연결은 하트비트가 끊겨 만료로 사라진다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnChatRealtime
public class RedisChatPresence implements ChatPresence {

    private static final RedisScript<Long> CONNECT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[5])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('ZADD', KEYS[2], 'GT', ARGV[2], ARGV[3])
            return 1
            """, Long.class);
    private static final RedisScript<Long> DISCONNECT = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1])
            local live = redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[2], '+inf')
            if live == 0 then
                redis.call('ZREM', KEYS[2], ARGV[3])
            end
            return live
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ChatRedisKeys chatRedisKeys;
    private final Duration ttl;
    private final String instanceId = UUID.randomUUID().toString();

    /** 접속 정보 만료(90s)는 미확정 초기값(Q12)이다. 하트비트 주기보다 충분히 길게 둔다 */
    public RedisChatPresence(StringRedisTemplate stringRedisTemplate, ChatRedisKeys chatRedisKeys,
            @Value("${hapbang.chat.realtime.presence.ttl:90s}") Duration ttl) {
        Assert.isTrue(ttl.isPositive(), "hapbang.chat.realtime.presence.ttl은 0보다 커야 합니다.");
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatRedisKeys = chatRedisKeys;
        this.ttl = ttl;
    }

    @Override
    public void connected(Long userId, String sessionId) {
        long now = System.currentTimeMillis();
        long expiresAt = now + ttl.toMillis();
        stringRedisTemplate.execute(CONNECT, List.of(chatRedisKeys.presenceUser(userId), chatRedisKeys.presenceOnline()),
                member(sessionId), String.valueOf(expiresAt), String.valueOf(userId), String.valueOf(ttl.toMillis()),
                String.valueOf(now));
    }

    @Override
    public void disconnected(Long userId, String sessionId) {
        stringRedisTemplate.execute(DISCONNECT,
                List.of(chatRedisKeys.presenceUser(userId), chatRedisKeys.presenceOnline()),
                member(sessionId), String.valueOf(System.currentTimeMillis()), String.valueOf(userId));
    }

    @Override
    public void heartbeat(Map<Long, Set<String>> sessionIdsByUserId) {
        if (sessionIdsByUserId.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + ttl.toMillis();
        byte[] online = bytes(chatRedisKeys.presenceOnline());
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            sessionIdsByUserId.forEach((userId, sessionIds) -> {
                byte[] userKey = bytes(chatRedisKeys.presenceUser(userId));
                // 살아 있는 연결이 키 만료를 늘리므로, 끊김을 기록하지 못한 연결(재시작 전 앱 등)은 여기서 지운다
                connection.zSetCommands().zRemRangeByScore(userKey, Double.NEGATIVE_INFINITY, now);
                for (String sessionId : sessionIds) {
                    connection.zSetCommands().zAdd(userKey, expiresAt, bytes(member(sessionId)), ZAddArgs.empty());
                }
                connection.keyCommands().pExpire(userKey, ttl.toMillis());
                connection.zSetCommands().zAdd(online, expiresAt, bytes(String.valueOf(userId)),
                        ZAddArgs.empty().gt());
            });
            return null;
        });
    }

    @Override
    public void purgeExpired() {
        stringRedisTemplate.opsForZSet().removeRangeByScore(chatRedisKeys.presenceOnline(), Double.NEGATIVE_INFINITY,
                System.currentTimeMillis());
    }

    @Override
    public Set<Long> onlineAmong(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(userIds);
        byte[][] members = ids.stream().map(id -> bytes(String.valueOf(id))).toArray(byte[][]::new);
        List<Double> scores = stringRedisTemplate.execute((RedisCallback<List<Double>>) connection ->
                connection.zSetCommands().zMScore(bytes(chatRedisKeys.presenceOnline()), members));
        Set<Long> online = new HashSet<>();
        long now = System.currentTimeMillis();
        for (int i = 0; scores != null && i < ids.size(); i++) {
            Double score = scores.get(i);
            if (score != null && score > now) {
                online.add(ids.get(i));
            }
        }
        return online;
    }

    private String member(String sessionId) {
        return instanceId + ":" + sessionId;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
