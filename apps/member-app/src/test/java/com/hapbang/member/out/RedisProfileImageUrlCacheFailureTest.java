package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 프로필 사진 주소 캐시의 Redis 장애 동작 (#770). Redis가 실패해도 예외 없이 비어 있는 것처럼 동작하고,
 * 실패가 쌓이면 회로가 열려 Redis를 부르지 않는다. 실제 Redis를 멈춘 확인은 API 통합 테스트에서 한다
 */
class RedisProfileImageUrlCacheFailureTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private RedisProfileImageUrlCache cache;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.multiGet(anyList())).willThrow(new RedisConnectionFailureException("redis down"));
        given(redisTemplate.executePipelined(any(SessionCallback.class)))
                .willThrow(new RedisConnectionFailureException("redis down"));
        cache = new RedisProfileImageUrlCache(redisTemplate, meterRegistry);
    }

    @Test
    @DisplayName("Redis가 실패하면 읽기는 비어 있고 저장은 예외 없이 넘어가며, 못 읽은 주소 수를 unavailable로 센다")
    void redisFailure() {
        assertThat(cache.findAll(List.of("member/profile/1/a", "member/profile/1/b"))).isEmpty();
        assertThatCode(() -> cache.putAll(List.of(
                new ProfileImageUrlCache.Entry("member/profile/1/a", "https://s3.example/a", Duration.ofMinutes(50)))))
                .doesNotThrowAnyException();

        assertThat(unavailable()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패가 10번 쌓이면 회로가 열려 Redis를 부르지 않고 바로 비어 있다")
    void circuitOpens() {
        for (int i = 0; i < 10; i++) {
            cache.findAll(List.of("member/profile/1/a"));
        }
        reset(valueOperations);

        assertThat(cache.findAll(List.of("member/profile/1/a"))).isEmpty();

        verify(valueOperations, never()).multiGet(anyList());
        assertThat(unavailable()).isEqualTo(11);
        assertThat(meterRegistry.get("resilience4j.circuitbreaker.state")
                .tag("name", RedisProfileImageUrlCache.CIRCUIT_NAME).tag("state", "open").gauge().value())
                .isEqualTo(1);
    }

    private double unavailable() {
        return meterRegistry.get(RedisProfileImageUrlCache.METRIC_NAME).tag("result", "unavailable").counter().count();
    }
}
