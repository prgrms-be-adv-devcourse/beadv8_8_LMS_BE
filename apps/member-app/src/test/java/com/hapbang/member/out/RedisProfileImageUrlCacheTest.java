package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import io.micrometer.core.instrument.MeterRegistry;

/** 프로필 사진 주소 캐시 (#770). 실제 Redis로 저장·일괄 조회·TTL·지표를 확인한다. 장애 동작은 RedisProfileImageUrlCacheFailureTest */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisProfileImageUrlCacheTest {

    @Autowired
    RedisProfileImageUrlCache cache;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys.stream().map(RedisProfileImageUrlCache::redisKey).toList());
    }

    @Test
    @DisplayName("저장한 주소를 MGET으로 함께 읽고, 없는 key는 빠진다")
    void putAllThenFindAll() {
        String first = newKey();
        String second = newKey();
        String missing = newKey();

        cache.putAll(List.of(new ProfileImageUrlCache.Entry(first, "https://s3.example/first", Duration.ofMinutes(50)),
                new ProfileImageUrlCache.Entry(second, "https://s3.example/second", Duration.ofMinutes(50))));

        assertThat(cache.findAll(List.of(first, second, missing)))
                .containsOnly(entry(first, "https://s3.example/first"),
                        entry(second, "https://s3.example/second"));
    }

    @Test
    @DisplayName("키는 member:profile-image-url:{S3 key}이고, 넘긴 TTL 뒤에 사라진다")
    void keyAndTtl() {
        String key = newKey();

        cache.putAll(List.of(new ProfileImageUrlCache.Entry(key, "https://s3.example/a", Duration.ofMinutes(25))));

        String redisKey = "member:profile-image-url:" + key;
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("https://s3.example/a");
        assertThat(redisTemplate.getExpire(redisKey, TimeUnit.SECONDS))
                .isBetween(Duration.ofMinutes(24).toSeconds(), Duration.ofMinutes(25).toSeconds());
    }

    @Test
    @DisplayName("읽은 주소마다 hit·miss 지표를 센다")
    void metrics() {
        String cached = newKey();
        cache.putAll(List.of(new ProfileImageUrlCache.Entry(cached, "https://s3.example/a", Duration.ofMinutes(50))));
        double hits = count("hit");
        double misses = count("miss");

        cache.findAll(List.of(cached, newKey(), newKey()));

        assertThat(count("hit") - hits).isEqualTo(1);
        assertThat(count("miss") - misses).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 목록이면 Redis를 부르지 않고 비어 있다")
    void empty() {
        assertThat(cache.findAll(List.of())).isEmpty();
        cache.putAll(List.of());
    }

    private double count(String result) {
        return meterRegistry.get(RedisProfileImageUrlCache.METRIC_NAME).tag("result", result).counter().count();
    }

    private String newKey() {
        String key = "member/profile/1/" + UUID.randomUUID();
        createdKeys.add(key);
        return key;
    }
}
