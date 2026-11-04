package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.hapbang.chat.app.ChatDeliveryAvailability;
import com.hapbang.chat.app.ChatDeliveryProperties;
import com.hapbang.chat.app.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.ChatJobStream;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 읽기 전용(복제본·failover) Redis에서 PING만으로 재개하지 않는다 (Codex 리뷰). 전용 redis:8 컨테이너를 존재하지 않는
 * 마스터의 복제본으로 바꾸면 PING은 성공하지만 XADD·XREADGROUP은 READONLY로 거절된다. 재확인은 작은 쓰기로 하므로 계속 실패하고,
 * 간격이 두 배씩 늘며, 다시 마스터가 되면 재개한다.
 */
class RedisReadOnlyRecoveryTest {

    private static final Duration PROBE_INITIAL = Duration.ofMillis(50);
    private static final Duration PROBE_MAX = Duration.ofMillis(800);

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private CountingQueue queue;
    private ChatDeliveryAvailability availability;

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
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        queue = new CountingQueue(redis, factory, new ChatRedisKeys("readonly-" + UUID.randomUUID()));
        availability = new ChatDeliveryAvailability(queue, properties(), noMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        becomeMaster();
        queue.destroy();
        factory.destroy();
    }

    @Test
    void 읽기_전용이면_PING은_되어도_재개하지_않고_재확인_간격이_늘며_마스터가_되면_재개한다() throws Exception {
        queue.ensureGroup(ChatJobStream.DELIVERY);
        queue.enqueue(ChatJobStream.DELIVERY, Map.of("jobId", "before"));

        becomeReplicaOfMissingMaster();
        assertThat(redis.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
        Throwable rejected = catchThrowable(() -> queue.enqueue(ChatJobStream.DELIVERY, Map.of("jobId", "x")));
        assertThat(rejected).isInstanceOf(ChatDeliveryUnavailableException.class);
        assertThat(RedisFailures.hasMessage(rejected, "READONLY")).isTrue();
        assertThat(catchThrowable(() -> queue.readNew(ChatJobStream.DELIVERY, "c1", 1, Duration.ofMillis(10))))
                .isInstanceOf(ChatDeliveryUnavailableException.class);
        availability.redisUnavailable(rejected);

        // 2초 동안 실행기처럼 자주 묻는다: 한 번도 열리지 않아야 하고(깜빡임 없음) 재확인 간격은 두 배씩 는다
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        boolean everOpened = false;
        while (System.nanoTime() < until) {
            everOpened |= availability.redisAvailable();
            Thread.sleep(5);
        }
        assertThat(everOpened).as("READONLY 동안 재개(깜빡임)").isFalse();
        List<Long> probes = queue.probeTimes();
        // 50·100·200·400·800ms 간격이면 2초 동안 5~6번이다. PING으로 열고 닫기를 반복하면 훨씬 많다
        assertThat(probes).hasSizeBetween(3, 7);
        long firstGap = probes.get(1) - probes.get(0);
        long lastGap = probes.get(probes.size() - 1) - probes.get(probes.size() - 2);
        assertThat(lastGap).as("재확인 간격이 늘어난다").isGreaterThan(firstGap * 3);

        becomeMaster();
        await().atMost(PROBE_MAX.plusSeconds(2)).pollInterval(Duration.ofMillis(10))
                .until(availability::redisAvailable);
        assertThat(queue.enqueue(ChatJobStream.DELIVERY, Map.of("jobId", "after"))).isNotBlank();
    }

    private void becomeReplicaOfMissingMaster() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        int port = closedPort;
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().replicaOf("127.0.0.1", port);
            return null;
        });
    }

    private void becomeMaster() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().replicaOfNoOne();
            return null;
        });
    }

    /** 재확인(ping) 시각을 남기는 큐 */
    private static final class CountingQueue extends RedisChatDeliveryQueue {

        private final List<Long> probeTimes = new ArrayList<>();

        CountingQueue(StringRedisTemplate redis, LettuceConnectionFactory factory, ChatRedisKeys keys) {
            super(redis, factory, keys);
        }

        @Override
        public void ping() {
            synchronized (probeTimes) {
                probeTimes.add(System.nanoTime());
            }
            super.ping();
        }

        List<Long> probeTimes() {
            synchronized (probeTimes) {
                return List.copyOf(probeTimes);
            }
        }
    }

    private static ChatDeliveryProperties properties() {
        ChatDeliveryProperties.Backoff backoff = new ChatDeliveryProperties.Backoff(Duration.ofMillis(1),
                Duration.ofMillis(1), 0);
        return new ChatDeliveryProperties(
                new ChatDeliveryProperties.Pipeline(true),
                "test",
                new ChatDeliveryProperties.Relay(Duration.ofMillis(200), 50, Duration.ofSeconds(30), 10, backoff),
                new ChatDeliveryProperties.Consumer(2, 1, 20, Duration.ofMillis(50), 10, backoff),
                new ChatDeliveryProperties.Reclaim(Duration.ofSeconds(5), Duration.ofSeconds(30), 20, 100, 50),
                new ChatDeliveryProperties.Reconstruct(Duration.ofSeconds(60), Duration.ofMinutes(5),
                        Duration.ofMinutes(5), 5, 100),
                new ChatDeliveryProperties.Trim(Duration.ofMinutes(10), Duration.ofHours(24)),
                new ChatDeliveryProperties.Pause(PROBE_INITIAL, PROBE_MAX),
                Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMeterRegistry() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
