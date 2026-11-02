package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.shared.member.domain.Role;

import tools.jackson.databind.json.JsonMapper;

/**
 * 웹소켓 연결 티켓 저장소와 로그아웃 표식을 실제 Redis로 확인한다(설계 문서 6장): 해시 키, GETDEL 한 번 사용(동시 사용 포함), TTL,
 * 고정 창 발급 횟수(INCR + 첫 발급만 PEXPIRE), 표식 MGET. 다른 테스트와 나누지 않도록 전용 컨테이너를 쓴다.
 */
class RedisChatConnectionTicketStoreTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    private static final Instant ISSUED = Instant.parse("2026-11-02T01:00:00Z");

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private ChatRedisKeys keys;
    private RedisChatConnectionTicketStore store;
    private RedisChatLogoutMarkers markers;

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
        keys = new ChatRedisKeys("ticket-" + UUID.randomUUID());
        store = new RedisChatConnectionTicketStore(redis, JsonMapper.shared(), keys);
        markers = new RedisChatLogoutMarkers(redis, keys, Duration.ofMinutes(20));
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void 티켓은_원문이_아닌_SHA_256_키에_TTL과_함께_두고_한_번만_꺼낸다() {
        String ticket = "A".repeat(43);
        ChatConnectionTicket value = new ChatConnectionTicket(7L, Role.VERIFIED_INFLUENCER, ISSUED,
                ISSUED.plusSeconds(900));

        store.save(ticket, value, Duration.ofSeconds(30));

        String key = keys.connectionTicket(RedisChatConnectionTicketStore.sha256Hex(ticket));
        assertThat(key).doesNotContain(ticket).matches(".*:chat:ws-ticket:[0-9a-f]{64}");
        assertThat(redis.getExpire(key)).isBetween(1L, 30L);
        assertThat(redis.opsForValue().get(key)).doesNotContain(ticket);
        assertThat(store.consume(ticket)).contains(value);
        assertThat(store.consume(ticket)).isEmpty();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void 같은_티켓을_동시에_써도_한쪽만_값을_받는다() throws Exception {
        String ticket = "B".repeat(43);
        store.save(ticket, new ChatConnectionTicket(7L, Role.INFLUENCER, ISSUED, ISSUED.plusSeconds(900)),
                Duration.ofSeconds(30));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Optional<ChatConnectionTicket>>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    start.await();
                    return store.consume(ticket);
                });
            }
            List<Future<Optional<ChatConnectionTicket>>> results = new ArrayList<>();
            for (Callable<Optional<ChatConnectionTicket>> task : tasks) {
                results.add(executor.submit(task));
            }
            start.countDown();
            int received = 0;
            for (Future<Optional<ChatConnectionTicket>> result : results) {
                received += result.get().isPresent() ? 1 : 0;
            }
            assertThat(received).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 유효_시간이_지난_티켓은_꺼낼_수_없다() throws Exception {
        String ticket = "C".repeat(43);
        store.save(ticket, new ChatConnectionTicket(7L, Role.INFLUENCER, ISSUED, ISSUED.plusSeconds(900)),
                Duration.ofSeconds(1));

        Thread.sleep(1200);

        assertThat(store.consume(ticket)).isEmpty();
    }

    @Test
    void 발급_횟수는_창의_첫_발급만_만료를_걸고_창이_지나면_다시_1부터_센다() throws Exception {
        assertThat(store.incrementIssueCount(7L, Duration.ofSeconds(1))).isEqualTo(1);
        long ttl = redis.getExpire(keys.connectionTicketRate(7L), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(store.incrementIssueCount(7L, Duration.ofSeconds(1))).isEqualTo(2);
        // 두 번째 발급이 만료를 늘리지 않는다
        assertThat(redis.getExpire(keys.connectionTicketRate(7L), java.util.concurrent.TimeUnit.MILLISECONDS))
                .isLessThanOrEqualTo(ttl).isPositive();
        assertThat(store.incrementIssueCount(8L, Duration.ofSeconds(1))).isEqualTo(1);

        Thread.sleep(1200);

        assertThat(store.incrementIssueCount(7L, Duration.ofSeconds(1))).isEqualTo(1);
    }

    @Test
    void 로그아웃_표식은_더_이른_값으로_앞당기지_않고_깨진_값은_덮는다() {
        Instant later = ISSUED.plusSeconds(10);
        markers.markLoggedOut(7L, later);
        // 아웃박스 재전송으로 늦게 다시 온 옛 로그아웃
        markers.markLoggedOut(7L, ISSUED);
        markers.markLoggedOut(7L, later);
        redis.opsForValue().set(keys.logoutMarker(9L), "not-a-number");
        markers.markLoggedOut(9L, ISSUED);

        assertThat(markers.loggedOutAt(Set.of(7L, 9L))).containsExactlyInAnyOrderEntriesOf(Map.of(7L, later, 9L, ISSUED));
        assertThat(redis.getExpire(keys.logoutMarker(9L))).isBetween(1L, 20L * 60);
    }

    @Test
    void 로그아웃_표식은_더_늦은_값으로_덮고_한_번에_읽으며_없거나_깨진_값은_빠진다() {
        Instant first = ISSUED;
        Instant later = ISSUED.plusSeconds(10);
        markers.markLoggedOut(7L, first);
        markers.markLoggedOut(7L, later);
        markers.markLoggedOut(8L, first);
        redis.opsForValue().set(keys.logoutMarker(9L), "not-a-number");

        Map<Long, Instant> read = markers.loggedOutAt(Set.of(7L, 8L, 9L, 10L));

        assertThat(read).containsExactlyInAnyOrderEntriesOf(Map.of(7L, later, 8L, first));
        assertThat(redis.getExpire(keys.logoutMarker(7L))).isBetween(1L, 20L * 60);
        assertThat(markers.loggedOutAt(Set.of())).isEmpty();
    }
}
