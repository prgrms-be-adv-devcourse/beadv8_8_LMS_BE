package com.hapbang.member.out;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 프로필 사진 조회 주소를 Redis에 둔다 (#770). 키 {@code member:profile-image-url:{S3 key}}, 값은 주소, TTL은 부르는 쪽이 정한다.
 * <ul>
 *     <li>읽기는 {@code MGET} 한 번, 쓰기는 없을 때만 넣는 {@code SET NX EX}와 {@code GET}을 pipeline으로 한 번에 보낸다.</li>
 *     <li>Redis 호출은 회로 {@code profile-image-url-cache}({@link MemberOutboundCircuitBreakers})로 감싼다. Redis가 멈추면 회로가 열려
 *     Redis를 부르지 않고 바로 비어 있는 것처럼 동작한다(Redis 시간 제한 1초를 요청마다 기다리지 않는다).
 *     회로가 열리면 WARN, 다시 닫히면 INFO를 한 번씩 남긴다.</li>
 *     <li>지표: {@code member.profile_image.url_cache}({@code result}=hit·miss·unavailable, 주소 단위),
 *     {@code resilience4j.circuitbreaker.*}({@code name}=profile-image-url-cache).</li>
 *     <li>주소는 서명이 들어 있어 로그에 남기지 않는다. 이미 응답으로 브라우저에 가는 값이라 Redis에 두어도 노출 범위는 늘지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisProfileImageUrlCache implements ProfileImageUrlCache {

    static final String KEY_PREFIX = "member:profile-image-url:";
    static final String METRIC_NAME = "member.profile_image.url_cache";

    private final StringRedisTemplate redisTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Counter hits;
    private final Counter misses;
    private final Counter unavailable;

    /** 회로는 회원의 다른 회로와 함께 {@link MemberOutboundCircuitBreakers}가 만들고 지표도 거기서 낸다 */
    public RedisProfileImageUrlCache(StringRedisTemplate redisTemplate, MemberOutboundCircuitBreakers circuitBreakers,
                                     MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreakers.circuitBreaker(MemberOutboundCircuitBreakers.Target.PROFILE_IMAGE_URL_CACHE);
        this.circuitBreaker.getEventPublisher().onStateTransition(event -> logTransition(event.getStateTransition()));
        this.hits = counter(meterRegistry, "hit");
        this.misses = counter(meterRegistry, "miss");
        this.unavailable = counter(meterRegistry, "unavailable");
    }

    @Override
    public Lookup findAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return new Lookup(Map.of(), true);
        }
        List<String> keyList = List.copyOf(keys);
        Optional<List<String>> values = call("읽지", () -> redisTemplate.opsForValue()
                .multiGet(keyList.stream().map(RedisProfileImageUrlCache::redisKey).toList()));
        if (values.isEmpty()) {
            unavailable.increment(keyList.size());
            return Lookup.unavailable();
        }
        Map<String, String> found = new HashMap<>();
        for (int i = 0; i < keyList.size(); i++) {
            String value = values.get().get(i);
            if (value != null) {
                found.put(keyList.get(i), value);
            }
        }
        hits.increment(found.size());
        misses.increment(keyList.size() - found.size());
        return new Lookup(found, true);
    }

    /** key마다 {@code SET NX EX}와 {@code GET}을 pipeline 한 번으로 보낸다. GET은 먼저 있던 주소 또는 방금 넣은 주소다 */
    @Override
    public Map<String, String> putAllIfAbsent(List<Entry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        Optional<List<Object>> results = call("저장하지", () -> redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (Entry entry : entries) {
                    String redisKey = redisKey(entry.key());
                    operations.opsForValue().setIfAbsent(redisKey, entry.url(), entry.ttl());
                    operations.opsForValue().get(redisKey);
                }
                return null;
            }
        }));
        if (results.isEmpty()) {
            return Map.of();
        }
        Map<String, String> stored = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            // 결과는 [SET NX 결과, GET 결과]가 key마다 이어진다
            if (results.get().get(i * 2 + 1) instanceof String url) {
                stored.put(entries.get(i).key(), url);
            }
        }
        return stored;
    }

    /**
     * 회로 안에서 Redis를 부른다. 실패하거나 회로가 열려 있으면 비어 있다.
     * <p>
     * 실패는 종류를 가리지 않고 "캐시 없음"으로 본다. Redis 오류({@code DataAccessException})뿐 아니라 앱 종료 중 연결 팩토리가 멈춰
     * 나는 {@code IllegalStateException} 등도 프로필·목록 응답을 500으로 만들지 않게 한다
     */
    private <T> Optional<T> call(String action, Supplier<T> redisCall) {
        try {
            return Optional.ofNullable(circuitBreaker.executeSupplier(redisCall));
        } catch (CallNotPermittedException e) {
            // 회로가 열려 있다. 열릴 때 WARN을 한 번 남겼으므로 요청마다 남기지 않는다
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("프로필 사진 주소 캐시를 {} 못해 캐시 없이 서명한다", action, e);
            return Optional.empty();
        }
    }

    private static void logTransition(CircuitBreaker.StateTransition transition) {
        switch (transition.getToState()) {
            case OPEN -> log.warn("프로필 사진 주소 캐시 회로가 열려 Redis 없이 서명한다 transition={}", transition);
            case CLOSED -> log.info("프로필 사진 주소 캐시 회로가 닫혀 Redis를 다시 쓴다 transition={}", transition);
            default -> {
            }
        }
    }

    private static Counter counter(MeterRegistry meterRegistry, String result) {
        return Counter.builder(METRIC_NAME)
                .description("프로필 사진 조회 주소 캐시 결과 (주소 단위)")
                .tag("result", result)
                .register(meterRegistry);
    }

    static String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
