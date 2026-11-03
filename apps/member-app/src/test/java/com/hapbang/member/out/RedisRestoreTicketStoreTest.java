package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** 복구 티켓 Redis 저장소 (#593). 실제 Redis로 발급·조회·삭제와 만료 시간을 확인한다 */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisRestoreTicketStoreTest {

    @Autowired
    RedisRestoreTicketStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("발급한 티켓으로 회원 ID를 찾고, 티켓 원문은 키에 남기지 않으며 10분 뒤 사라진다")
    void issue() {
        String ticket = issue(42L);

        assertThat(store.find(ticket)).contains(42L);
        String key = RedisRestoreTicketStore.KEY_PREFIX + RedisRestoreTicketStore.hash(ticket);
        assertThat(key).doesNotContain(ticket);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                .isBetween(Duration.ofMinutes(9).toSeconds(), RedisRestoreTicketStore.TTL.toSeconds());
    }

    @Test
    @DisplayName("조회해도 지우지 않아 다시 쓸 수 있고, 지우면 더는 찾을 수 없다")
    void findThenDelete() {
        String ticket = issue(42L);

        assertThat(store.find(ticket)).contains(42L);
        assertThat(store.find(ticket)).contains(42L);
        store.delete(ticket);

        assertThat(store.find(ticket)).isEmpty();
    }

    @Test
    @DisplayName("발급할 때마다 다른 티켓이고, 모르는 티켓이나 빈 값은 찾지 못한다")
    void unknown() {
        String first = issue(42L);
        String second = issue(42L);

        assertThat(first).isNotEqualTo(second);
        assertThat(store.find("unknown-ticket")).isEmpty();
        assertThat(store.find("")).isEmpty();
        assertThat(store.find(null)).isEmpty();
    }

    private String issue(Long userId) {
        String ticket = store.issue(userId);
        createdKeys.add(RedisRestoreTicketStore.KEY_PREFIX + RedisRestoreTicketStore.hash(ticket));
        return ticket;
    }
}
