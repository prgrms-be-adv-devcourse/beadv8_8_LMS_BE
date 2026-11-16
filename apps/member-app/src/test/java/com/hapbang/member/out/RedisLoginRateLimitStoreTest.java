package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** 로그인 IP 한도 (#506): 실제 Redis로 확인한다 (기본값: 1분 10번) */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisLoginRateLimitStoreTest {

    @Autowired
    RedisLoginRateLimitStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("10번까지 받고 11번째부터 남은 시간(1분 이하)을 돌려준다")
    void rejectAfterLimit() {
        String ipKey = newIpKey();

        for (int i = 0; i < 10; i++) {
            assertThat(store.tryAcquire(ipKey)).isZero();
        }

        assertThat(store.tryAcquire(ipKey)).isBetween(1L, Duration.ofMinutes(1).toMillis());
        assertThat(store.tryAcquire(ipKey)).isBetween(1L, Duration.ofMinutes(1).toMillis());
    }

    @Test
    @DisplayName("IP마다 따로 센다")
    void separatePerIp() {
        String first = newIpKey();
        String second = newIpKey();
        for (int i = 0; i < 11; i++) {
            store.tryAcquire(first);
        }

        assertThat(store.tryAcquire(second)).isZero();
    }

    @Test
    @DisplayName("키는 IP 원문이 아니라 해시이고, 첫 요청부터 1분 뒤 저절로 사라진다 (TTL)")
    void hashedKeyWithTtl() {
        String ipKey = newIpKey();

        store.tryAcquire(ipKey);

        String key = RedisLoginRateLimitStore.KEY_PREFIX + RedisLoginAttemptStore.hash(ipKey);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1");
        assertThat(redisTemplate.getExpire(key)).isBetween(55L, 60L);
        assertThat(redisTemplate.keys(RedisLoginRateLimitStore.KEY_PREFIX + "*")).noneMatch(k -> k.contains(ipKey));
    }

    private String newIpKey() {
        // 테스트끼리 겹치지 않는 세는 단위 (실제 값 모양과 상관없이 문자열로만 쓴다)
        String ipKey = "test-" + UUID.randomUUID();
        createdKeys.add(RedisLoginRateLimitStore.KEY_PREFIX + RedisLoginAttemptStore.hash(ipKey));
        return ipKey;
    }
}
