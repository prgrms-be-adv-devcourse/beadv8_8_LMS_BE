package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** refresh token Redis 저장소 (#370): 회전·재사용 감지·유예 시간·폐기를 실제 Redis로 확인한다 */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisRefreshTokenStoreTest {

    /** application.yaml이 아니라 RefreshTokenProperties의 기본값(유예 10초)으로 뜬다 */
    private static final Duration GRACE = Duration.ofSeconds(10);

    @Autowired
    RedisRefreshTokenStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("저장한 토큰을 회전하면 그 회원으로 Rotated이고, 새 토큰도 이어서 회전할 수 있다")
    void rotate_chain() {
        Long userId = newUserId();
        String first = newHash();
        String second = newHash();
        String third = newHash();
        store.save(userId, first);

        assertThat(store.rotate(first, second, Instant.now())).isEqualTo(new RotationResult.Rotated(userId));
        assertThat(store.rotate(second, third, Instant.now())).isEqualTo(new RotationResult.Rotated(userId));
    }

    @Test
    @DisplayName("저장된 적 없는 토큰은 NotFound")
    void rotate_unknown() {
        newUserId();

        assertThat(store.rotate(newHash(), newHash(), Instant.now())).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("회전된 토큰이 유예 시간 안에 다시 오면 재사용으로 보지 않고 Grace이고, 새 토큰은 저장하지 않는다 (여러 탭 동시 재발급)")
    void rotate_reuseWithinGrace() {
        Long userId = newUserId();
        String old = newHash();
        String next = newHash();
        String notSaved = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, next, now);

        RotationResult again = store.rotate(old, notSaved, now.plus(GRACE).minusMillis(1));

        assertThat(again).isEqualTo(new RotationResult.Grace(userId));
        assertThat(redisTemplate.hasKey(RedisRefreshTokenStore.TOKEN_KEY_PREFIX + notSaved)).isFalse();
        assertThat(redisTemplate.opsForSet().members(RedisRefreshTokenStore.USER_KEY_PREFIX + userId))
                .containsExactly(next);
    }

    @Test
    @DisplayName("유예 시간 안에 같은 옛 토큰이 여러 번 와도 새 토큰이 갈라지지 않는다 (첫 회전의 새 토큰 하나만 유효)")
    void rotate_reuseWithinGrace_repeated() {
        Long userId = newUserId();
        String old = newHash();
        String next = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, next, now);

        List<String> attempts = List.of(newHash(), newHash(), newHash());
        for (String attempt : attempts) {
            assertThat(store.rotate(old, attempt, now.plusSeconds(1))).isEqualTo(new RotationResult.Grace(userId));
        }

        assertThat(attempts).allSatisfy(attempt ->
                assertThat(store.rotate(attempt, newHash(), now.plusSeconds(2))).isEqualTo(new RotationResult.NotFound()));
        assertThat(redisTemplate.opsForSet().members(RedisRefreshTokenStore.USER_KEY_PREFIX + userId))
                .containsExactly(next);
    }

    @Test
    @DisplayName("회전으로 받은 새 토큰을 로그아웃으로 폐기하면, 유예 시간 안이라도 옛 토큰은 거절된다")
    void rotate_withinGrace_afterNextRevoked() {
        Long userId = newUserId();
        String old = newHash();
        String next = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, next, now);

        store.revoke(next);

        assertThat(store.rotate(old, newHash(), now.plusSeconds(1))).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("회원의 토큰을 모두 폐기한 직후에는 유예 시간 안의 옛 토큰도 거절된다 (재사용 감지·정지 뒤 새 토큰 발급 막기)")
    void rotate_withinGrace_afterRevokeAll() {
        Long userId = newUserId();
        String old = newHash();
        String next = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, next, now);

        store.revokeAll(userId);

        assertThat(store.rotate(old, newHash(), now.plusSeconds(1))).isEqualTo(new RotationResult.NotFound());
        assertThat(store.rotate(next, newHash(), now.plusSeconds(1))).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("회전으로 받은 새 토큰이 이미 다시 회전됐으면, 유예 시간 안이라도 옛 토큰은 거절된다")
    void rotate_withinGrace_afterNextRotated() {
        Long userId = newUserId();
        String old = newHash();
        String next = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, next, now);
        store.rotate(next, newHash(), now.plusSeconds(1));

        assertThat(store.rotate(old, newHash(), now.plusSeconds(2))).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("회전된 토큰이 유예 시간이 지나 다시 오면 Reused (탈취 의심)")
    void rotate_reuseAfterGrace() {
        Long userId = newUserId();
        String old = newHash();
        store.save(userId, old);
        Instant now = Instant.now();
        store.rotate(old, newHash(), now);

        RotationResult again = store.rotate(old, newHash(), now.plus(GRACE).plusMillis(1));

        assertThat(again).isEqualTo(new RotationResult.Reused(userId));
    }

    @Test
    @DisplayName("폐기한 토큰은 회전할 수 없고, 폐기하면 그 토큰의 회원 ID를 돌려준다 (로그아웃 이벤트용, #539)")
    void revoke() {
        Long userId = newUserId();
        String token = newHash();
        store.save(userId, token);

        assertThat(store.revoke(token)).contains(userId);

        assertThat(store.rotate(token, newHash(), Instant.now())).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("없거나 이미 폐기된 토큰을 폐기하면 빈 값이다")
    void revoke_unknown() {
        Long userId = newUserId();
        String token = newHash();
        store.save(userId, token);
        store.revoke(token);

        assertThat(store.revoke(token)).isEmpty();
        assertThat(store.revoke(newHash())).isEmpty();
    }

    @Test
    @DisplayName("회원의 토큰을 모두 폐기하면 기기마다 받은 토큰이 모두 회전되지 않고, 다른 회원 토큰은 남는다")
    void revokeAll() {
        Long userId = newUserId();
        Long otherUserId = newUserId();
        String phone = newHash();
        String laptop = newHash();
        String other = newHash();
        store.save(userId, phone);
        store.save(userId, laptop);
        store.save(otherUserId, other);

        store.revokeAll(userId);

        assertThat(store.rotate(phone, newHash(), Instant.now())).isEqualTo(new RotationResult.NotFound());
        assertThat(store.rotate(laptop, newHash(), Instant.now())).isEqualTo(new RotationResult.NotFound());
        assertThat(store.rotate(other, newHash(), Instant.now())).isEqualTo(new RotationResult.Rotated(otherUserId));
    }

    @Test
    @DisplayName("회전으로 받은 새 토큰도 모두 폐기 대상이다")
    void revokeAll_includesRotatedToken() {
        Long userId = newUserId();
        String old = newHash();
        String rotated = newHash();
        store.save(userId, old);
        store.rotate(old, rotated, Instant.now());

        store.revokeAll(userId);

        assertThat(store.rotate(rotated, newHash(), Instant.now())).isEqualTo(new RotationResult.NotFound());
    }

    @Test
    @DisplayName("저장한 토큰과 회전으로 받은 토큰은 14일 뒤 Redis에서 저절로 사라진다")
    void ttl() {
        Long userId = newUserId();
        String saved = newHash();
        String rotated = newHash();
        store.save(userId, saved);
        store.rotate(saved, rotated, Instant.now());

        assertThat(ttlSeconds(saved)).isBetween(Duration.ofDays(14).minusMinutes(1).toSeconds(), Duration.ofDays(14).toSeconds());
        assertThat(ttlSeconds(rotated)).isBetween(Duration.ofDays(14).minusMinutes(1).toSeconds(), Duration.ofDays(14).toSeconds());
    }

    @Test
    @DisplayName("토큰 원문이 아니라 받은 해시만 키로 저장한다")
    void storesOnlyHash() {
        Long userId = newUserId();
        String hash = newHash();

        store.save(userId, hash);

        assertThat(redisTemplate.opsForHash().get(RedisRefreshTokenStore.TOKEN_KEY_PREFIX + hash, "userId"))
                .isEqualTo(String.valueOf(userId));
        assertThat(redisTemplate.opsForSet().members(RedisRefreshTokenStore.USER_KEY_PREFIX + userId))
                .containsExactly(hash);
    }

    private Long ttlSeconds(String hash) {
        return redisTemplate.getExpire(RedisRefreshTokenStore.TOKEN_KEY_PREFIX + hash, TimeUnit.SECONDS);
    }

    private String newHash() {
        String hash = UUID.randomUUID().toString();
        createdKeys.add(RedisRefreshTokenStore.TOKEN_KEY_PREFIX + hash);
        return hash;
    }

    private Long newUserId() {
        Long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        createdKeys.add(RedisRefreshTokenStore.USER_KEY_PREFIX + userId);
        return userId;
    }
}
