package com.hapbang.chat.out.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.hapbang.chat.app.ChatLogoutMarkers;
import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 로그아웃 표식을 Redis에 둔다(설계 문서 6장·Q8). 키 {@link ChatRedisKeys#logoutMarker(long)}, 값은 로그아웃 시각(epoch ms).
 * 쓰기는 {@code redis/scripts/logout-marker.lua}로 이미 있는 값보다 늦을 때만 한다(늦게 다시 온 옛 이벤트가 표식을 앞당기지 않게).
 * TTL은 가장 긴 연결(access token 수명 15분)보다 길게 둔다(초기값 20분, Q12). 그보다 오래된 표식은 이미 만료로 닫힌 연결에만 해당한다.
 * 읽기는 하트비트마다 로컬 회원을 {@code MGET} 한 번으로 읽는다.
 */
@Slf4j
@Component
@ConditionalOnChatRealtime
public class RedisChatLogoutMarkers implements ChatLogoutMarkers {

    private static final RedisScript<Long> MARK = RedisLuaSupport.longLuaScript("redis/scripts/logout-marker.lua");

    private final StringRedisTemplate stringRedisTemplate;
    private final ChatRedisKeys chatRedisKeys;
    private final Duration ttl;

    public RedisChatLogoutMarkers(StringRedisTemplate stringRedisTemplate, ChatRedisKeys chatRedisKeys,
            @Value("${hapbang.chat.realtime.logout-marker.ttl:20m}") Duration ttl) {
        Assert.isTrue(ttl.isPositive(), "hapbang.chat.realtime.logout-marker.ttl은 0보다 커야 합니다.");
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatRedisKeys = chatRedisKeys;
        this.ttl = ttl;
    }

    @Override
    public void markLoggedOut(Long userId, Instant loggedOutAt) {
        RedisLuaSupport.runForLong(stringRedisTemplate, MARK, List.of(chatRedisKeys.logoutMarker(userId)),
                loggedOutAt.toEpochMilli(), ttl.toMillis());
    }

    @Override
    public Map<Long, Instant> loggedOutAt(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(userIds);
        List<String> values = stringRedisTemplate.opsForValue()
                .multiGet(ids.stream().map(chatRedisKeys::logoutMarker).toList());
        Map<Long, Instant> markers = new HashMap<>();
        if (values == null) {
            return markers;
        }
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            try {
                markers.put(ids.get(i), Instant.ofEpochMilli(Long.parseLong(value)));
            } catch (NumberFormatException exception) {
                log.warn("로그아웃 표식 형식 오류를 무시함 userId={}", ids.get(i));
            }
        }
        return markers;
    }
}
