package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 방 채널 동적 구독: 참조 수, 동시 구독, 해제 유예와 유예 중 재구독, 앱 상한, Redis 명령을 잠금 밖에서 순서대로 하기, 실패·시간 초과
 * 때 구독 재설정, 늦게 끝난 명령 무시, 해제 실패 뒤에도 이어지는 주기 정리, 해제 대기도 세는 상한, 컨테이너보다 먼저 멈추기.
 */
class RedisRoomChannelSubscriptionsTest {

    private static final long IO_TIMEOUT_MILLIS = 3_000;

    private final ChatRedisKeys keys = new ChatRedisKeys("test-room-channels");
    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final MessageListener listener = mock(MessageListener.class);
    private final List<RedisRoomChannelSubscriptions> created = new ArrayList<>();
    private final ExecutorService callers = Executors.newFixedThreadPool(8);

    @AfterEach
    void tearDown() {
        created.forEach(RedisRoomChannelSubscriptions::destroy);
        callers.shutdownNow();
    }

    @Test
    void 같은_방의_동시_구독은_채널을_한_번만_더한다() throws Exception {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(callers.submit(() -> {
                start.await();
                return channels.acquire(5L);
            }));
        }
        start.countDown();
        for (Future<Boolean> result : results) {
            assertThat(result.get()).isTrue();
        }

        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(5L));
        verify(container, after(200).times(1)).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        assertThat(channels.subscribedChannels()).isEqualTo(1);
    }

    @Test
    void 유예가_0이면_마지막_참조가_빠질_때_바로_뺀다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 100);
        channels.acquire(5L);
        channels.acquire(5L);
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(5L));

        channels.release(5L);
        verify(container, after(200).never()).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        channels.release(5L);

        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(5L));
        assertThat(channels.subscribedChannels()).isZero();
    }

    @Test
    void 유예_중_다시_구독하면_빼지_않고_그대로_쓴다() throws Exception {
        RedisRoomChannelSubscriptions channels = channels(Duration.ofMillis(100), 100);
        channels.acquire(5L);
        channels.release(5L);

        assertThat(channels.acquire(5L)).isTrue();
        Thread.sleep(150);
        channels.reapIdle();

        verify(container, after(200).times(1)).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        verify(container, never()).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
    }

    @Test
    void 유예가_지난_채널은_정리할_때_뺀다() throws Exception {
        RedisRoomChannelSubscriptions channels = channels(Duration.ofMillis(50), 100);
        channels.acquire(5L);
        channels.release(5L);

        Thread.sleep(80);
        channels.reapIdle();

        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(5L));
        assertThat(channels.subscribedChannels()).isZero();
    }

    @Test
    void 앱_상한이면_유예_중인_채널을_먼저_정리하고_그래도_넘으면_더하지_않는다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ofHours(1), 2);
        channels.acquire(1L);
        channels.acquire(2L);
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(2L));
        channels.release(2L);

        assertThat(channels.acquire(3L)).as("유예 중인 2번을 정리하고 더한다").isTrue();
        assertThat(channels.acquire(4L)).as("1·3번은 쓰는 중").isFalse();

        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(2L));
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(3L));
        verify(container, after(200).never()).addMessageListener(listener, topic(4L));
    }

    @Test
    void Redis_구독_확인을_기다리는_동안에도_잠금을_잡지_않아_다른_방의_구독과_해제가_막히지_않는다() throws Exception {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 100);
        CountDownLatch redisSlow = new CountDownLatch(1);
        CountDownLatch subscribing = new CountDownLatch(1);
        doAnswer(invocation -> {
            subscribing.countDown();
            redisSlow.await();
            return null;
        }).when(container).addMessageListener(listener, topic(1L));
        try {
            Future<Boolean> first = callers.submit(() -> channels.acquire(1L));
            assertThat(first.get(1, TimeUnit.SECONDS)).as("구독 확인을 기다리지 않고 돌아온다").isTrue();
            assertThat(subscribing.await(IO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

            // 구독 확인이 늦는 동안 다른 방의 구독·해제와 연결 종료 정리가 잠금에서 기다리지 않는다
            assertThat(callers.submit(() -> channels.acquire(2L)).get(1, TimeUnit.SECONDS)).isTrue();
            callers.submit(() -> channels.release(2L)).get(1, TimeUnit.SECONDS);
            callers.submit(() -> channels.release(1L)).get(1, TimeUnit.SECONDS);
            assertThat(callers.submit(channels::subscribedChannels).get(1, TimeUnit.SECONDS)).isZero();
        } finally {
            redisSlow.countDown();
        }

        // 늦은 구독 확인이 끝나면 그 사이 바뀐 상태(모두 해제)로 맞춘다
        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(1L));
    }

    @Test
    void 해제_직후_다시_구독하면_명령을_순서대로_맞춰_마지막_상태가_남는다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 100);
        List<String> commands = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> commands.add("add " + invocation.<ChannelTopic>getArgument(1).getTopic()))
                .when(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        doAnswer(invocation -> commands.add("remove " + invocation.<ChannelTopic>getArgument(1).getTopic()))
                .when(container).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        String room5 = topic(5L).getTopic();

        channels.acquire(5L);
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(5L));
        channels.release(5L);
        channels.acquire(5L);
        channels.release(5L);
        channels.acquire(5L);
        verify(container, after(300).atMost(2)).addMessageListener(listener, topic(5L));

        List<String> forRoom = commands.stream().filter(command -> command.endsWith(room5)).toList();
        assertThat(forRoom.getLast()).as("마지막 요청(구독)이 마지막 명령이다").isEqualTo("add " + room5);
        for (int i = 1; i < forRoom.size(); i++) {
            assertThat(forRoom.get(i)).as("같은 명령이 연달아 가지 않는다").isNotEqualTo(forRoom.get(i - 1));
        }

        channels.release(5L);
        verify(container, timeout(IO_TIMEOUT_MILLIS).atLeastOnce()).removeMessageListener(listener, topic(5L));
        verify(container, after(200).atLeastOnce()).removeMessageListener(listener, topic(5L));
        assertThat(commands.stream().filter(command -> command.endsWith(room5)).toList().getLast())
                .isEqualTo("remove " + room5);
    }

    @Test
    void 구독_추가가_실패하면_그_방만_고치지_않고_구독을_재설정해_원하는_방으로_다시_맞춘다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 100);
        doThrow(new RedisConnectionFailureException("Redis 끊김"))
                .when(container).addMessageListener(listener, topic(5L));

        channels.acquire(6L);
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(6L));
        channels.acquire(5L);

        InOrder reset = inOrder(container);
        reset.verify(container, timeout(IO_TIMEOUT_MILLIS)).stop();
        reset.verify(container).removeMessageListener(listener);
        reset.verify(container).addMessageListener(eq(listener), argThat(topicsOf(5L, 6L)));
        reset.verify(container).start();
        verify(container, after(300).times(1)).addMessageListener(listener, topic(5L));
        verify(container, never()).removeMessageListener(listener, topic(5L));
        assertThat(channels.subscribedChannels()).isEqualTo(2);
    }

    @Test
    void 제한_시간을_넘겨_늦게_끝난_해제는_무시하고_재설정한_상태를_흔들지_않는다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 1);
        CountDownLatch lateDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            Thread.sleep(6_000);
            lateDone.countDown();
            return null;
        }).when(container).removeMessageListener(listener, topic(1L));
        channels.acquire(1L);
        verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(1L));

        channels.release(1L);
        // 5초 제한을 넘기면 구독을 재설정하고, 해제 대기가 풀려 상한 1에서 다른 방을 받는다
        await().atMost(Duration.ofSeconds(8)).until(() -> channels.acquire(2L));
        await().atMost(Duration.ofSeconds(5)).until(() -> lateDone.getCount() == 0);

        verify(container, after(1_500).times(1)).addMessageListener(listener, topic(1L));
        verify(container, times(1)).removeMessageListener(listener, topic(1L));
    }

    @Test
    void 주기_정리는_구독_해제가_실패해도_멈추지_않는다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ofMillis(50), 100);
        doThrow(new RedisConnectionFailureException("Redis 끊김"))
                .when(container).removeMessageListener(listener, topic(1L));
        channels.acquire(1L);
        channels.release(1L);
        // 주기 정리(1초 간격)가 1번 해제에 실패한다
        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(1L));

        channels.acquire(2L);
        channels.release(2L);

        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(2L));
        assertThat(channels.subscribedChannels()).isZero();
    }

    @Test
    void Redis가_느린_동안_해제를_기다리는_방도_상한으로_세어_대기_작업이_쌓이지_않는다() {
        RedisRoomChannelSubscriptions channels = channels(Duration.ZERO, 1);
        CountDownLatch redisSlow = new CountDownLatch(1);
        doAnswer(invocation -> {
            redisSlow.await();
            return null;
        }).when(container).addMessageListener(listener, topic(1L));
        int accepted = 0;
        try {
            channels.acquire(1L);
            verify(container, timeout(IO_TIMEOUT_MILLIS)).addMessageListener(listener, topic(1L));
            channels.release(1L);
            // 1번 구독 확인이 늦는 동안 다른 방을 구독했다 놓기를 되풀이한다
            for (long roomId = 2; roomId <= 1000; roomId++) {
                if (channels.acquire(roomId)) {
                    accepted++;
                    channels.release(roomId);
                }
            }
        } finally {
            redisSlow.countDown();
        }

        assertThat(accepted).as("해제를 마치지 못한 1번이 상한을 차지한다").isZero();
        verify(container, timeout(IO_TIMEOUT_MILLIS)).removeMessageListener(listener, topic(1L));
        await().atMost(Duration.ofMillis(IO_TIMEOUT_MILLIS)).until(() -> channels.acquire(2L));
    }

    @Test
    void 컨테이너보다_먼저_멈추고_끝나지_않는_구독_확인에_묶이지_않아_실행기_스레드가_끝난다() {
        RedisMessageListenerContainer lifecycleContainer = mock(RedisMessageListenerContainer.class);
        when(lifecycleContainer.isRunning()).thenReturn(true);
        when(lifecycleContainer.isAutoStartup()).thenReturn(true);
        when(lifecycleContainer.getPhase()).thenReturn(SmartLifecycle.DEFAULT_PHASE);
        CountDownLatch subscribing = new CountDownLatch(1);
        // 컨테이너가 멈춘 직후의 추가처럼 오지 않는 구독 확인을 인터럽트로도 풀리지 않게 기다린다
        doAnswer(invocation -> {
            subscribing.countDown();
            return new CompletableFuture<Void>().join();
        }).when(lifecycleContainer).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        AtomicBoolean ioAliveAtContainerStop = new AtomicBoolean(true);
        Set<Thread> before = ioThreads();
        doAnswer(invocation -> {
            ioAliveAtContainerStop.set(newIoThreads(before).stream().anyMatch(Thread::isAlive));
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(lifecycleContainer).stop(any(Runnable.class));

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
        context.registerBean(ChatRealtimeRedisConfig.CONTAINER_BEAN_NAME, RedisMessageListenerContainer.class,
                () -> lifecycleContainer);
        context.registerBean(ChatRealtimeRedisConfig.LISTENER_BEAN_NAME, MessageListener.class, () -> listener);
        context.registerBean(ChatRedisKeys.class, () -> keys);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(RedisRoomChannelSubscriptions.class);
        context.refresh();
        context.getBean(RedisRoomChannelSubscriptions.class).acquire(5L);
        await().atMost(Duration.ofMillis(IO_TIMEOUT_MILLIS)).until(() -> subscribing.getCount() == 0);

        long started = System.nanoTime();
        context.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(ioAliveAtContainerStop).as("컨테이너가 멈출 때 실행기는 이미 끝났다").isFalse();
        assertThat(newIoThreads(before)).as("멈춘 뒤 실행기 스레드가 남지 않는다").noneMatch(Thread::isAlive);
    }

    private static Set<Thread> ioThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("chat-room-channel-io"))
                .collect(Collectors.toSet());
    }

    private static Set<Thread> newIoThreads(Set<Thread> before) {
        Set<Thread> threads = ioThreads();
        threads.removeAll(before);
        return threads;
    }

    private ArgumentMatcher<Collection<? extends Topic>> topicsOf(long... roomIds) {
        Set<Topic> rooms = new HashSet<>();
        for (long roomId : roomIds) {
            rooms.add(topic(roomId));
        }
        return topics -> topics != null && new HashSet<>(topics).equals(rooms);
    }

    private ChannelTopic topic(long roomId) {
        return new ChannelTopic(keys.roomChannel(roomId));
    }

    private RedisRoomChannelSubscriptions channels(Duration grace, int max) {
        RedisRoomChannelSubscriptions channels = new RedisRoomChannelSubscriptions(container, listener, keys,
                new SimpleMeterRegistry(), grace, max);
        created.add(channels);
        return channels;
    }
}
