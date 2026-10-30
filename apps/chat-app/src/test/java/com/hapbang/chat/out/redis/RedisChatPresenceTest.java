package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Redis 접속 정보(설계 Q21)를 실제 Redis로 확인한다: 연결·끊김(마지막 연결만 지움), 만료로 정리, 다른 앱의 연결,
 * 1000명 중 접속 회원을 한 번에 찾기, 회원 연결의 만료된 원소 정리. 다른 테스트와 나누지 않도록 전용 컨테이너를 쓴다.
 */
class RedisChatPresenceTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private ChatRedisKeys keys;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),
                REDIS.getMappedPort(6379)), LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        keys = new ChatRedisKeys("presence-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void 마지막_연결이_끊길_때만_접속_중_목록에서_뺀다() {
        RedisChatPresence presence = presence(Duration.ofSeconds(90));

        presence.connected(7L, "s-1");
        presence.connected(7L, "s-2");
        presence.connected(8L, "s-3");
        assertThat(presence.onlineAmong(List.of(7L, 8L, 9L))).containsExactlyInAnyOrder(7L, 8L);

        presence.disconnected(7L, "s-1");
        assertThat(presence.onlineAmong(List.of(7L))).containsExactly(7L);
        presence.disconnected(7L, "s-2");
        assertThat(presence.onlineAmong(List.of(7L, 8L))).containsExactly(8L);
        assertThat(redis.opsForZSet().size(keys.presenceUser(7L))).isZero();
    }

    @Test
    void 하트비트가_멈춘_연결은_만료로_접속_중이_아니고_정리하면_지워진다() throws Exception {
        RedisChatPresence crashed = presence(Duration.ofMillis(200));
        crashed.connected(7L, "s-1");
        assertThat(crashed.onlineAmong(List.of(7L))).containsExactly(7L);

        Thread.sleep(300);
        assertThat(crashed.onlineAmong(List.of(7L))).isEmpty();
        crashed.purgeExpired();
        assertThat(redis.opsForZSet().size(keys.presenceOnline())).isZero();
        assertThat(redis.hasKey(keys.presenceUser(7L))).as("회원 연결 키도 만료된다").isFalse();
    }

    @Test
    void 하트비트는_만료를_늘린다() throws Exception {
        RedisChatPresence presence = presence(Duration.ofMillis(400));
        presence.connected(7L, "s-1");

        for (int i = 0; i < 4; i++) {
            Thread.sleep(150);
            presence.heartbeat(Map.of(7L, Set.of("s-1")));
        }

        assertThat(presence.onlineAmong(List.of(7L))).containsExactly(7L);
    }

    @Test
    void 살아_있는_연결이_키_만료를_늘려도_만료된_회원_연결은_하트비트와_연결_때_지운다() {
        RedisChatPresence live = presence(Duration.ofSeconds(90));
        String userKey = keys.presenceUser(7L);
        live.connected(7L, "s-1");
        // 재시작 전 앱의 연결: 끊김을 기록하지 못해 만료된 점수로 남았다
        redis.opsForZSet().add(userKey, "old-app:s-1", System.currentTimeMillis() - 1_000);

        live.heartbeat(Map.of(7L, Set.of("s-1")));
        assertThat(redis.opsForZSet().size(userKey)).as("하트비트가 만료된 연결을 지운다").isEqualTo(1);

        redis.opsForZSet().add(userKey, "old-app:s-2", System.currentTimeMillis() - 1_000);
        live.connected(7L, "s-2");
        assertThat(redis.opsForZSet().range(userKey, 0, -1)).as("연결이 만료된 연결을 지운다")
                .noneMatch(member -> member.startsWith("old-app:")).hasSize(2);
        assertThat(live.onlineAmong(List.of(7L))).containsExactly(7L);
    }

    @Test
    void 다시_시작한_앱의_옛_연결이_끊겨도_다른_앱의_연결은_남는다() {
        RedisChatPresence before = presence(Duration.ofSeconds(90));
        RedisChatPresence other = presence(Duration.ofSeconds(90));
        before.connected(7L, "s-1");
        other.connected(7L, "s-1");

        before.disconnected(7L, "s-1");

        assertThat(other.onlineAmong(List.of(7L))).containsExactly(7L);
    }

    @Test
    void 천명_중_접속_회원을_한_번에_찾는다() {
        RedisChatPresence presence = presence(Duration.ofSeconds(90));
        Map<Long, Set<String>> sessions = new HashMap<>();
        for (long id = 1; id <= 1000; id += 3) {
            sessions.put(id, Set.of("s-" + id));
        }
        presence.heartbeat(sessions);
        Set<Long> all = new HashSet<>();
        for (long id = 1; id <= 1000; id++) {
            all.add(id);
        }

        assertThat(presence.onlineAmong(all)).isEqualTo(sessions.keySet());
    }

    private RedisChatPresence presence(Duration ttl) {
        return new RedisChatPresence(redis, keys, ttl);
    }
}
