package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;
import org.springframework.data.redis.connection.lettuce.LettuceSubscription;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 방 채널 동적 구독을 실제 Redis와 실제 구독 컨테이너로 확인한다: 해제 명령이 실패하면 구독을 재설정해 실제 Redis 구독과 드라이버가
 * 기억하는 채널이 원하는 방과 같아지고, 그때까지 그 방이 상한을 차지하며, 재설정이 서버 채널을 다시 구독해 재구독 신호를 낸다. 해제
 * 실패는 Lettuce 구독 객체의 해제 명령에 넣는다. 다른 테스트와 나누지 않도록 전용 컨테이너를 쓴다.
 */
class RedisRoomChannelSubscriptionsRedisTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final AtomicBoolean failNextUnsubscribe = new AtomicBoolean();
    private final AtomicInteger activitySubscribed = new AtomicInteger();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisMessageListenerContainer container;
    private RedisRoomChannelSubscriptions channels;
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
        factory = new FailingUnsubscribeConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),
                REDIS.getMappedPort(6379)), LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .build(), failNextUnsubscribe);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        keys = new ChatRedisKeys("room-channels-" + UUID.randomUUID());
        RecordingListener listener = new RecordingListener(keys.activityChannel(), activitySubscribed);
        // 운영과 같이 서버 채널 두 개를 늘 구독하는 컨테이너에 방 채널을 더하고 뺀다
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.addMessageListener(listener, List.of(new ChannelTopic(keys.activityChannel()),
                new ChannelTopic(keys.controlChannel())));
        container.afterPropertiesSet();
        container.start();
        channels = new RedisRoomChannelSubscriptions(container, listener, keys, new SimpleMeterRegistry(),
                Duration.ZERO, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        channels.destroy();
        container.destroy();
        factory.destroy();
    }

    @Test
    void 해제가_거듭_실패해도_구독을_재설정해_Redis와_드라이버가_원하는_방만_구독하고_재구독_신호를_낸다() {
        assertThat(channels.acquire(1L)).isTrue();
        await().atMost(WAIT).until(() -> subscribers(1L) == 1);
        assertThat(activitySubscribed).hasValue(1);

        for (int failure = 0; failure < 3; failure++) {
            failNextUnsubscribe.set(true);
            channels.release(1L);
            await().atMost(WAIT).until(() -> !failNextUnsubscribe.get());
            await().atMost(WAIT).until(() -> subscribers(1L) == 0);
            // 해제를 확인하기 전에는 상한 1을 1번이 차지한다. 재설정이 끝나면 다시 받는다
            await().atMost(WAIT).until(() -> channels.acquire(1L));
            await().atMost(WAIT).until(() -> subscribers(1L) == 1);
        }
        channels.release(1L);
        await().atMost(WAIT).until(() -> subscribers(1L) == 0);

        await().atMost(WAIT).untilAsserted(() -> assertThat(trackedChannels())
                .as("드라이버가 기억하는 채널은 서버 채널 두 개뿐이다").hasSize(2));
        assertThat(activitySubscribed.get()).as("재설정이 서버 채널을 다시 구독해 REALTIME_RESUBSCRIBED 재동기화 신호를 낸다")
                .isGreaterThan(1);
    }

    /** 컨테이너의 지금 구독 연결에서 드라이버(Lettuce 구독 객체)가 기억하는 채널 */
    private List<String> trackedChannels() {
        Object subscriber = ReflectionTestUtils.getField(container, "subscriber");
        RedisConnection connection = (RedisConnection) ReflectionTestUtils.getField(subscriber, "connection");
        return connection.getSubscription().getChannels().stream()
                .map(channel -> new String(channel, StandardCharsets.UTF_8)).toList();
    }

    private long subscribers(long roomId) {
        Long count = redis.execute(new DefaultRedisScript<>("return redis.call('PUBSUB', 'NUMSUB', ARGV[1])[2]",
                Long.class), List.of(), keys.roomChannel(roomId));
        return count == null ? 0 : count;
    }

    /**
     * 서버 채널의 구독 확인 수를 센다. 실제 구독 진입 어댑터는 서버 채널의 두 번째 구독 확인부터 재구독으로 보고 모든 로컬 연결에
     * {@code RESYNC(REALTIME_RESUBSCRIBED)}를 보낸다({@code ChatRealtimeRedisListenerTest}).
     */
    private record RecordingListener(String activityChannel, AtomicInteger activitySubscribed)
            implements MessageListener, SubscriptionListener {

        @Override
        public void onMessage(Message message, byte[] pattern) {
        }

        @Override
        public void onChannelSubscribed(byte[] channel, long count) {
            if (activityChannel.equals(new String(channel, StandardCharsets.UTF_8))) {
                activitySubscribed.incrementAndGet();
            }
        }
    }

    /** 다음 채널 해제 명령 한 번을 Redis에 보내기 전에 실패시키는 연결 팩터리 */
    private static final class FailingUnsubscribeConnectionFactory extends LettuceConnectionFactory {

        private final AtomicBoolean failNextUnsubscribe;

        FailingUnsubscribeConnectionFactory(RedisStandaloneConfiguration configuration,
                LettuceClientConfiguration clientConfiguration, AtomicBoolean failNextUnsubscribe) {
            super(configuration, clientConfiguration);
            this.failNextUnsubscribe = failNextUnsubscribe;
        }

        @Override
        protected LettuceConnection doCreateLettuceConnection(StatefulRedisConnection<byte[], byte[]> sharedConnection,
                LettuceConnectionProvider connectionProvider, long timeout, int database) {
            return new LettuceConnection(sharedConnection, connectionProvider, timeout, database) {
                @Override
                protected LettuceSubscription doCreateSubscription(MessageListener listener,
                        StatefulRedisPubSubConnection<byte[], byte[]> connection,
                        LettuceConnectionProvider provider) {
                    return new LettuceSubscription(listener, connection, provider) {
                        @Override
                        protected void doUnsubscribe(boolean all, byte[]... channels) {
                            if (!all && failNextUnsubscribe.compareAndSet(true, false)) {
                                throw new RedisConnectionFailureException("주입한 해제 실패");
                            }
                            super.doUnsubscribe(all, channels);
                        }
                    };
                }
            };
        }
    }
}
