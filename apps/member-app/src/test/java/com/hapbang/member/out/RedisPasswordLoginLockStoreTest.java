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
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ClientIpKey;
import com.hapbang.member.out.PasswordLoginLockStore.FailureResult;
import com.hapbang.member.out.PasswordLoginLockStore.Lock;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** 비밀번호 로그인 잠금 (#506): 실제 Redis로 확인한다 (기본값: (이메일, IP) 5번·15분, 이메일 전체 1시간 20번·15분, 성공 IP 30일) */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisPasswordLoginLockStoreTest {

    /** 필드 하나의 남은 TTL(ms). 없으면 -2, TTL이 없으면 -1 */
    private static final DefaultRedisScript<Long> FIELD_PTTL = new DefaultRedisScript<>(
            "return redis.call('HPTTL', KEYS[1], 'FIELDS', 1, ARGV[1])[1]", Long.class);

    @Autowired
    RedisPasswordLoginLockStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("한 IP에서 5번째 실패에 그 IP만 잠기고, 다른 IP는 그대로다")
    void lockPerIp() {
        String email = newEmail();
        ClientIpKey attacker = ip("203.0.113.1");

        for (int i = 0; i < 4; i++) {
            assertThat(store.recordFailure(email, attacker)).isEqualTo(new FailureResult(false, false));
        }
        assertThat(store.check(email, attacker)).isEqualTo(Lock.NONE);

        assertThat(store.recordFailure(email, attacker)).isEqualTo(new FailureResult(true, false));
        assertThat(store.check(email, attacker)).isEqualTo(Lock.EMAIL_IP);
        assertThat(store.check(email, ip("203.0.113.2"))).isEqualTo(Lock.NONE);
        assertThat(store.check(email, null)).isEqualTo(Lock.NONE);
    }

    @Test
    @DisplayName("IP 여러 개로 20번째 실패에 이메일 전체가 잠기고, 성공한 적 있는 IP만 빠진다")
    void lockEmailExceptKnownIp() {
        String email = newEmail();
        ClientIpKey owner = ip("198.51.100.1");
        store.recordSuccess(email, owner);

        FailureResult last = null;
        for (int i = 0; i < 20; i++) {
            // IP 5개에서 4번씩: IP별 잠금에는 닿지 않는다
            last = store.recordFailure(email, ip("203.0.113." + (i % 5 + 10)));
        }

        assertThat(last).isEqualTo(new FailureResult(false, true));
        assertThat(store.check(email, ip("203.0.113.99"))).isEqualTo(Lock.EMAIL);
        assertThat(store.check(email, null)).isEqualTo(Lock.EMAIL);
        assertThat(store.check(email, owner)).isEqualTo(Lock.NONE);
    }

    @Test
    @DisplayName("성공하면 그 IP의 실패 수만 지운다 (이메일 전체 실패 수는 남는다)")
    void successResetsOnlyThatIp() {
        String email = newEmail();
        ClientIpKey owner = ip("203.0.113.1");
        for (int i = 0; i < 4; i++) {
            store.recordFailure(email, owner);
        }

        store.recordSuccess(email, owner);

        for (int i = 0; i < 4; i++) {
            assertThat(store.recordFailure(email, owner).emailIpLocked()).isFalse();
        }
        assertThat(redisTemplate.opsForHash().get(RedisPasswordLoginLockStore.attemptKey(email), "fail:all"))
                .isEqualTo("8");
    }

    @Test
    @DisplayName("IP를 모르면 모르는 IP 하나로 세고, 성공해도 기억하지 않는다")
    void unknownIp() {
        String email = newEmail();
        for (int i = 0; i < 5; i++) {
            store.recordFailure(email, null);
        }
        assertThat(store.check(email, null)).isEqualTo(Lock.EMAIL_IP);

        store.recordSuccess(email, null);

        assertThat(redisTemplate.hasKey(knownKey(email))).isFalse();
    }

    @Test
    @DisplayName("재설정 잠금 풀기는 IP와 상관없이 모든 잠금을 지우고, 성공한 IP 기억은 남긴다")
    void unlockAll() {
        String email = newEmail();
        ClientIpKey owner = ip("198.51.100.1");
        store.recordSuccess(email, owner);
        for (int i = 0; i < 20; i++) {
            store.recordFailure(email, ip("203.0.113." + (i / 5 + 10)));
        }
        assertThat(store.check(email, ip("203.0.113.10"))).isEqualTo(Lock.EMAIL_IP);

        store.unlockAll(email);

        assertThat(store.check(email, ip("203.0.113.10"))).isEqualTo(Lock.NONE);
        assertThat(store.check(email, ip("203.0.113.99"))).isEqualTo(Lock.NONE);
        assertThat(redisTemplate.hasKey(knownKey(email))).isTrue();
    }

    @Test
    @DisplayName("잠금은 15분, 이메일 전체 실패 수는 1시간, 성공 IP는 30일 뒤 저절로 사라진다 (필드 TTL)")
    void fieldTtl() {
        String email = newEmail();
        ClientIpKey attacker = ip("203.0.113.1");
        ClientIpKey owner = ip("198.51.100.1");
        for (int i = 0; i < 5; i++) {
            store.recordFailure(email, attacker);
        }
        store.recordSuccess(email, owner);

        String attempt = RedisPasswordLoginLockStore.attemptKey(email);
        assertTtl(attempt, "lock:" + RedisPasswordLoginLockStore.ipField(attacker), Duration.ofMinutes(15));
        assertTtl(attempt, "fail:all", Duration.ofHours(1));
        assertTtl(knownKey(email), RedisPasswordLoginLockStore.ipField(owner), Duration.ofDays(30));
    }

    @Test
    @DisplayName("키·필드에는 이메일·IP 원문이 없다")
    void hashedKeys() {
        String email = newEmail();
        ClientIpKey attacker = ip("203.0.113.1");
        store.recordFailure(email, attacker);
        store.recordSuccess(email, attacker);

        assertThat(redisTemplate.keys("member:login-*")).noneMatch(key -> key.contains(email));
        assertThat(redisTemplate.opsForHash().keys(RedisPasswordLoginLockStore.attemptKey(email)))
                .noneMatch(field -> field.toString().contains("203.0.113.1"));
        assertThat(redisTemplate.opsForHash().keys(knownKey(email)))
                .noneMatch(field -> field.toString().contains("203.0.113.1"));
    }

    private void assertTtl(String key, String field, Duration expected) {
        Long ttl = redisTemplate.execute(FIELD_PTTL, List.of(key), field);
        assertThat(ttl).isBetween(expected.minusSeconds(5).toMillis(), expected.toMillis());
    }

    private static ClientIpKey ip(String address) {
        return ClientIpKey.from(address).orElseThrow();
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        createdKeys.add(RedisPasswordLoginLockStore.attemptKey(email));
        createdKeys.add(knownKey(email));
        return email;
    }

    private static String knownKey(String email) {
        return RedisPasswordLoginLockStore.KNOWN_IP_KEY_PREFIX + RedisLoginAttemptStore.hash(email);
    }
}
