package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 로그인 실패 횟수·잠금 (#499): 실제 Redis로 확인한다 (기본값: 5번, 15분) */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisLoginAttemptStoreTest {

    @Autowired
    RedisLoginAttemptStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("4번 틀릴 때까지는 잠기지 않고, 5번째에 잠긴다")
    void lockAfterFiveFailures() {
        String email = newEmail();

        for (int i = 0; i < 4; i++) {
            assertThat(store.recordFailure(email)).isFalse();
            assertThat(store.isLocked(email)).isFalse();
        }

        assertThat(store.recordFailure(email)).isTrue();
        assertThat(store.isLocked(email)).isTrue();
    }

    @Test
    @DisplayName("잠금은 15분 뒤 저절로 풀린다 (TTL)")
    void lockExpires() {
        String email = newEmail();
        for (int i = 0; i < 5; i++) {
            store.recordFailure(email);
        }

        Long ttl = redisTemplate.getExpire(RedisLoginAttemptStore.LOCK_KEY_PREFIX + RedisLoginAttemptStore.hash(email),
                TimeUnit.SECONDS);
        assertThat(ttl).isBetween(Duration.ofMinutes(15).minusSeconds(5).toSeconds(), Duration.ofMinutes(15).toSeconds());
    }

    @Test
    @DisplayName("성공해서 실패 횟수를 지우면 처음부터 다시 센다")
    void reset() {
        String email = newEmail();
        for (int i = 0; i < 4; i++) {
            store.recordFailure(email);
        }

        store.reset(email);

        for (int i = 0; i < 4; i++) {
            assertThat(store.recordFailure(email)).isFalse();
        }
        assertThat(store.isLocked(email)).isFalse();
    }

    @Test
    @DisplayName("이메일마다 따로 센다")
    void perEmail() {
        String locked = newEmail();
        String other = newEmail();
        for (int i = 0; i < 5; i++) {
            store.recordFailure(locked);
        }

        assertThat(store.isLocked(locked)).isTrue();
        assertThat(store.isLocked(other)).isFalse();
    }

    @Test
    @DisplayName("Redis 키에는 이메일 원문이 아니라 해시를 쓴다")
    void storesHashOnly() {
        String email = newEmail();
        store.recordFailure(email);

        assertThat(redisTemplate.keys("member:login-fail:*")).noneMatch(key -> key.contains(email));
        assertThat(redisTemplate.hasKey(RedisLoginAttemptStore.FAIL_KEY_PREFIX + RedisLoginAttemptStore.hash(email)))
                .isTrue();
    }

    @Test
    @DisplayName("잠금 풀기는 실패 횟수와 잠금을 모두 지운다 (비밀번호 재설정, #557)")
    void unlock() {
        String email = newEmail();
        for (int i = 0; i < 5; i++) {
            store.recordFailure(email);
        }
        store.recordFailure(email);
        assertThat(store.isLocked(email)).isTrue();

        store.unlock(email);

        assertThat(store.isLocked(email)).isFalse();
        assertThat(redisTemplate.hasKey(RedisLoginAttemptStore.FAIL_KEY_PREFIX + RedisLoginAttemptStore.hash(email))).isFalse();
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = RedisLoginAttemptStore.hash(email);
        createdKeys.add(RedisLoginAttemptStore.FAIL_KEY_PREFIX + hash);
        createdKeys.add(RedisLoginAttemptStore.LOCK_KEY_PREFIX + hash);
        return email;
    }
}
