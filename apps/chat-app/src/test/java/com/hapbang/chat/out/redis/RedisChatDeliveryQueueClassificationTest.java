package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.hapbang.chat.app.ChatDeliveryBackpressureException;
import com.hapbang.chat.app.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatQueueGroupMissingException;

/**
 * 실제 Redis·Lettuce·Spring Data Redis가 만든 예외 사슬로 연결 불가와 명령 오류의 분류를 확인한다 (재검증).
 * 손으로 만든 예외({@link RedisFailuresTest})가 아니라 서버 오류 응답과 실제 연결 거부·명령 시간 초과를 쓴다.
 * 명령 오류(WRONGTYPE·NOGROUP·BUSYGROUP·ERR·NOSCRIPT)는 멈춤(연결 불가)으로 가면 안 되고, 실제 연결 장애는 작업 실패로
 * 세면 안 된다. 다른 테스트와 Redis를 나누지 않도록 전용 컨테이너를 쓴다.
 */
class RedisChatDeliveryQueueClassificationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private ChatRedisKeys keys;
    private RedisChatDeliveryQueue queue;

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
        factory = factory(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofSeconds(2));
        redis = new StringRedisTemplate(factory);
        keys = new ChatRedisKeys("classify-" + UUID.randomUUID());
        queue = new RedisChatDeliveryQueue(redis, factory, keys);
    }

    @AfterEach
    void tearDown() {
        queue.destroy();
        factory.destroy();
    }

    @Test
    void WRONGTYPE은_연결_불가도_메모리_상한도_아닌_명령_오류로_그대로_던진다() {
        redis.opsForValue().set(keys.deliveryStream(), "문자열 키");

        Throwable thrown = catchThrowable(() -> queue.enqueue(ChatJobStream.DELIVERY, Map.of("jobId", "x")));

        assertThat(thrown).isNotNull()
                .isNotInstanceOf(ChatDeliveryUnavailableException.class)
                .isNotInstanceOf(ChatDeliveryBackpressureException.class);
        assertThat(RedisFailures.hasMessage(thrown, "WRONGTYPE")).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(thrown)).isFalse();
    }

    @Test
    void NOGROUP은_멈춤이_아니라_그룹_유실로_알린다() {
        assertThatThrownBy(() -> queue.readNew(ChatJobStream.DELIVERY, "c1", 1, Duration.ofMillis(10)))
                .isInstanceOf(ChatQueueGroupMissingException.class);
        redis.opsForStream().add(keys.deliveryStream(), Map.of("jobId", "x"));
        assertThatThrownBy(() -> queue.reclaim(ChatJobStream.DELIVERY, "c1", Duration.ZERO, "0-0", 1))
                .isInstanceOf(ChatQueueGroupMissingException.class);
    }

    @Test
    void BUSYGROUP은_정상이라_예외가_없다() {
        queue.ensureGroup(ChatJobStream.DELIVERY);
        assertThatCode(() -> queue.ensureGroup(ChatJobStream.DELIVERY)).doesNotThrowAnyException();
    }

    @Test
    void ERR_인자_오류와_NOSCRIPT는_연결_불가가_아니다() {
        queue.ensureGroup(ChatJobStream.DELIVERY);
        Throwable invalidId = catchThrowable(() -> queue.trimBefore(ChatJobStream.DELIVERY, "not-an-id"));
        assertThat(invalidId).isNotNull().isNotInstanceOf(ChatDeliveryUnavailableException.class);
        assertThat(RedisFailures.isConnectivityFailure(invalidId)).isFalse();

        byte[] key = keys.deliveryStream().getBytes(StandardCharsets.UTF_8);
        Throwable wrongArity = catchThrowable(() -> redis.execute((RedisCallback<Object>) connection ->
                connection.execute("XADD", key)));
        assertThat(wrongArity).isNotNull();
        assertThat(RedisFailures.hasMessage(wrongArity, "ERR")).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(wrongArity)).isFalse();
        assertThat(RedisFailures.isMemoryFull(wrongArity)).isFalse();

        Throwable noScript = catchThrowable(() -> redis.execute((RedisCallback<Object>) connection ->
                connection.scriptingCommands().evalSha("ffffffffffffffffffffffffffffffffffffffff", ReturnType.STATUS,
                        0)));
        assertThat(noScript).isNotNull();
        assertThat(RedisFailures.hasMessage(noScript, "NOSCRIPT")).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(noScript)).isFalse();
    }

    @Test
    void 명령_시간_초과는_연결_불가다() {
        // 명령 시간 제한(300ms)보다 긴 블로킹 읽기(2s): Lettuce 시간 초과 → Spring QueryTimeoutException
        LettuceConnectionFactory shortTimeout = factory(REDIS.getHost(), REDIS.getMappedPort(6379),
                Duration.ofMillis(300));
        RedisChatDeliveryQueue timingOut = new RedisChatDeliveryQueue(new StringRedisTemplate(shortTimeout),
                shortTimeout, keys);
        try {
            timingOut.ensureGroup(ChatJobStream.DELIVERY);
            assertThatThrownBy(() -> timingOut.readNew(ChatJobStream.DELIVERY, "c1", 1, Duration.ofSeconds(2)))
                    .isInstanceOf(ChatDeliveryUnavailableException.class);
        } finally {
            timingOut.destroy();
            shortTimeout.destroy();
        }
    }

    /**
     * {@link com.hapbang.chat.app.ChatDeliveryQueue} 계약: 큐에 연결할 수 없으면 모든 메서드가
     * {@link ChatDeliveryUnavailableException}을 던진다. 연결이 한 번도 맺어지지 않은 상태(예: Redis가 내려간 채 기동)에서
     * 확인한다.
     */
    @Test
    void 연결을_거부하면_적재_PING_회수_새_작업_읽기_모두_연결_불가다() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LettuceConnectionFactory unreachable = factory("127.0.0.1", closedPort, Duration.ofMillis(500));
        RedisChatDeliveryQueue down = new RedisChatDeliveryQueue(new StringRedisTemplate(unreachable), unreachable,
                keys);
        try {
            assertThatThrownBy(down::ping).isInstanceOf(ChatDeliveryUnavailableException.class);
            assertThatThrownBy(() -> down.enqueue(ChatJobStream.DELIVERY, Map.of("jobId", "x")))
                    .isInstanceOf(ChatDeliveryUnavailableException.class);
            assertThatThrownBy(() -> down.reclaim(ChatJobStream.DELIVERY, "c1", Duration.ZERO, "0-0", 1))
                    .isInstanceOf(ChatDeliveryUnavailableException.class);
            assertThatThrownBy(() -> down.readNew(ChatJobStream.DELIVERY, "c1", 1, Duration.ofMillis(10)))
                    .isInstanceOf(ChatDeliveryUnavailableException.class);
        } finally {
            down.destroy();
            unreachable.destroy();
        }
    }

    private static LettuceConnectionFactory factory(String host, int port, Duration timeout) {
        LettuceConnectionFactory created = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(timeout).build());
        created.afterPropertiesSet();
        created.start();
        return created;
    }
}
