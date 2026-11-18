package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.global.redis.ChatRealtimeListenerHolder;
import com.hapbang.chat.global.redis.ChatRedisKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 종료(destroy)와 payload 받기의 경쟁: 받기 확인과 처리 중 작업 수 올리기는 멈춤과 같은 잠금 안에서 한다. 그래서 destroy가 돌아온 뒤에는
 * 어떤 처리도 시작되지 않고, 그 전에 시작한 처리는 모두 끝나 있다. 재구독 알림도 같은 받기 잠금을 지나므로 멈춘 뒤에는 재동기화·재검사를
 * 예약하지 않는다.
 */
class ChatRealtimeRedisListenerPauseRaceTest {

    private final ChatRedisKeys keys = new ChatRedisKeys("test-pause-race");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 여러 구독 스레드가 payload를 계속 넘기는 중에 destroy를 부른다. 멈춤이 받기 확인과 작업 등록 사이에 끼어들면 destroy가 기다리지
     * 않은 작업이 destroy 뒤에 시작된다. 엇갈림은 우연에 맡기므로 정해진 횟수(시간 상한 안)만큼 되풀이한다.
     */
    @Test
    void 구독_스레드가_payload를_넘기는_중에_종료해도_종료_뒤에는_처리가_시작되지_않고_그_전_처리는_끝나_있다()
            throws Exception {
        int iterations = 100;
        int callbackThreads = 4;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        byte[] body = jsonMapper.writeValueAsBytes(fanout());
        int completed = 0;
        long deliveredBeforeDestroy = 0;
        for (int iteration = 0; iteration < iterations && System.nanoTime() < deadline; iteration++) {
            // 호출 기록을 남기지 않는다(회차마다 수천 번 불린다)
            ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class, withSettings().stubOnly());
            AtomicInteger started = new AtomicInteger();
            AtomicInteger finished = new AtomicInteger();
            doAnswer(invocation -> {
                started.incrementAndGet();
                // 처리 중인 시간을 조금 넓혀 destroy가 처리 중인 작업을 만나게 한다
                long until = System.nanoTime() + 20_000;
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                finished.incrementAndGet();
                return null;
            }).when(facade).deliverMessage(any());
            ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, mock(ChatRealtimeListenerHolder.class), 2, 1_000, 1,
                    10, Duration.ofSeconds(5), new SimpleMeterRegistry());
            AtomicBoolean firing = new AtomicBoolean(true);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> callbacks = new ArrayList<>();
            for (int t = 0; t < callbackThreads; t++) {
                long roomId = 10 + t;
                Thread callback = new Thread(() -> {
                    awaitQuietly(go);
                    byte[] channel = keys.roomChannel(roomId).getBytes(StandardCharsets.UTF_8);
                    while (firing.get()) {
                        listener.onMessage(new DefaultMessage(channel, body), null);
                    }
                });
                callbacks.add(callback);
                callback.start();
            }
            go.countDown();
            // 처리가 시작된 뒤 0~2ms 사이 아무 때나 멈춘다
            while (started.get() == 0) {
                Thread.onSpinWait();
            }
            long pauseAt = System.nanoTime() + ThreadLocalRandom.current().nextLong(2_000_000);
            while (System.nanoTime() < pauseAt) {
                Thread.onSpinWait();
            }

            listener.destroy();
            int startedAtDestroy = started.get();
            int finishedAtDestroy = finished.get();

            // 구독 스레드는 잠시 더 넘긴다(멈춘 뒤라 버린다)
            long fireUntil = System.nanoTime() + 1_000_000;
            while (System.nanoTime() < fireUntil) {
                Thread.onSpinWait();
            }
            firing.set(false);
            for (Thread callback : callbacks) {
                callback.join(5_000);
            }
            assertThat(finishedAtDestroy).as("%d회차: destroy 전에 시작한 처리는 끝나 있다", iteration)
                    .isEqualTo(startedAtDestroy);
            assertThat(started.get()).as("%d회차: destroy 뒤에는 처리가 시작되지 않는다", iteration)
                    .isEqualTo(startedAtDestroy);
            completed++;
            deliveredBeforeDestroy += startedAtDestroy;
        }
        assertThat(completed).as("시간 상한 안에 되풀이한 횟수").isGreaterThanOrEqualTo(20);
        assertThat(deliveredBeforeDestroy).isPositive();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static MessageFanout fanout() {
        ChatMessageResponse message = new ChatMessageResponse(1L, 12L, 1L, 3L, ChatMessageType.TEXT, "안녕",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 12L, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);
    }

    @Test
    void 종료한_뒤의_재구독_알림은_재동기화와_재검사를_예약하지_않는다() {
        ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, mock(ChatRealtimeListenerHolder.class), 1, 10, 1, 10,
                Duration.ofSeconds(1), meterRegistry);
        byte[] fanoutChannel = keys.activityChannel().getBytes(StandardCharsets.UTF_8);
        byte[] controlChannel = keys.controlChannel().getBytes(StandardCharsets.UTF_8);
        listener.onChannelSubscribed(fanoutChannel, 1);
        listener.onChannelSubscribed(controlChannel, 2);

        listener.destroy();
        listener.onChannelSubscribed(fanoutChannel, 1);
        listener.onChannelSubscribed(controlChannel, 2);

        verify(facade, after(300).never()).onMessageSubscriptionRecovered();
        verify(facade, never()).onControlSubscriptionRecovered();
        // 멈춘 뒤라 받지 않았다(닫힌 실행기가 거절해 버린 것이 아니다)
        assertThat(meterRegistry.counter(ChatRealtimeRedisListener.DROPPED_METRIC, "queue", "control").count())
                .isZero();
    }
}
