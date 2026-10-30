package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.out.redis.ChatRedisKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 멈춤(pause)과 처리 작업 등록의 경쟁: pause·awaitIdle이 끝난 뒤에는 어떤 처리도 시작되지 않아야 한다. 재구독 알림도 같은 받기 잠금을
 * 지나므로 멈춘 중에는 재동기화·재검사를 예약하지 않는다.
 */
class ChatRealtimeRedisListenerPauseRaceTest {

    private final ChatRedisKeys keys = new ChatRedisKeys("test-pause-race");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 콜백이 받기 확인을 통과한 직후(작업 수를 올리기 전) 선점된 사이에 pause·awaitIdle을 부른다. pause는 그 콜백이 작업을 등록할
     * 때까지 기다려야 하고, awaitIdle은 그 작업이 끝난 뒤에 돌아와야 한다. 그렇지 않으면 비운 뒤에 처리가 시작된다.
     */
    @Test
    void 받기_확인_직후_선점된_콜백이_있어도_멈추고_비운_뒤에는_처리가_시작되지_않는다() throws Exception {
        ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
        ConcurrentLinkedQueue<Long> started = new ConcurrentLinkedQueue<>();
        doAnswer(invocation -> {
            started.add(System.nanoTime());
            return null;
        }).when(facade).deliverMessage(any());
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch leaveWindow = new CountDownLatch(1);
        ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, 1, 10, 1, 10,
                Duration.ofSeconds(1), new SimpleMeterRegistry()) {
            @Override
            void afterAcceptCheck() {
                inWindow.countDown();
                try {
                    leaveWindow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        byte[] body = jsonMapper.writeValueAsBytes(fanout());
        Thread callback = new Thread(() -> listener.onMessage(
                new DefaultMessage(keys.roomChannel(12).getBytes(StandardCharsets.UTF_8), body), null));
        try {
            callback.start();
            assertThat(inWindow.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch paused = new CountDownLatch(1);
            AtomicLong drainedAt = new AtomicLong();
            Thread drainer = new Thread(() -> {
                listener.pause();
                paused.countDown();
                if (listener.awaitIdle(Duration.ofSeconds(5))) {
                    drainedAt.set(System.nanoTime());
                }
            });
            drainer.start();
            // 선점된 콜백이 작업을 등록하기 전에는 pause가 돌아오지 않는다
            assertThat(paused.await(300, TimeUnit.MILLISECONDS)).as("pause가 등록 중인 콜백을 기다리지 않음").isFalse();

            leaveWindow.countDown();
            drainer.join(5_000);
            assertThat(drainedAt.get()).as("awaitIdle 성공").isPositive();
            assertThat(started).hasSize(1).allMatch(at -> at < drainedAt.get());
        } finally {
            leaveWindow.countDown();
            callback.join(5_000);
            listener.destroy();
        }
    }

    /**
     * 콜백이 들어온 직후(받기 확인 전) 선점된 사이에 멈춤·비우기·재개가 모두 끝나도, 그 콜백의 옛 payload는 재개 뒤에 처리되지 않는다.
     * 콜백은 들어올 때의 받기 세대를 잡고, 멈춤·재개가 세대를 올리기 때문이다.
     */
    @Test
    void 받기_확인_전에_선점된_콜백은_멈춤_비우기_재개가_끝난_뒤에도_옛_payload를_처리하지_않는다() throws Exception {
        ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean parkFirst = new AtomicBoolean(true);
        ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, 1, 10, 1, 10,
                Duration.ofSeconds(1), new SimpleMeterRegistry()) {
            @Override
            void afterIntakeEntry() {
                if (parkFirst.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        proceed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        byte[] body = jsonMapper.writeValueAsBytes(fanout());
        Thread oldCallback = new Thread(() -> listener.onMessage(
                new DefaultMessage(keys.roomChannel(12).getBytes(StandardCharsets.UTF_8), body), null));
        try {
            oldCallback.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            listener.pause();
            assertThat(listener.awaitIdle(Duration.ofSeconds(1))).isTrue();
            listener.resume();
            proceed.countDown();
            oldCallback.join(5_000);

            verify(facade, after(300).never()).deliverMessage(any());
            // 재개 뒤에 새로 들어온 payload는 처리한다
            listener.onMessage(new DefaultMessage(keys.roomChannel(12).getBytes(StandardCharsets.UTF_8), body), null);
            verify(facade, timeout(2_000)).deliverMessage(any());
        } finally {
            proceed.countDown();
            listener.destroy();
        }
    }

    @Test
    void 멈춘_중의_재구독_알림은_재동기화와_재검사를_예약하지_않는다() {
        ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
        ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, 1, 10, 1, 10,
                Duration.ofSeconds(1), new SimpleMeterRegistry());
        try {
            byte[] fanoutChannel = keys.activityChannel().getBytes(StandardCharsets.UTF_8);
            byte[] controlChannel = keys.controlChannel().getBytes(StandardCharsets.UTF_8);
            listener.onChannelSubscribed(fanoutChannel, 1);
            listener.onChannelSubscribed(controlChannel, 2);

            listener.pause();
            listener.onChannelSubscribed(fanoutChannel, 1);
            listener.onChannelSubscribed(controlChannel, 2);
            assertThat(listener.awaitIdle(Duration.ofSeconds(1))).isTrue();

            verify(facade, after(300).never()).onMessageSubscriptionRecovered();
            verify(facade, never()).onControlSubscriptionRecovered();

            listener.resume();
            listener.onChannelSubscribed(fanoutChannel, 1);
            verify(facade, timeout(2_000)).onMessageSubscriptionRecovered();
        } finally {
            listener.destroy();
        }
    }

    private static MessageFanout fanout() {
        ChatMessageResponse message = new ChatMessageResponse(1L, 12L, 1L, 3L, ChatMessageType.TEXT, "안녕",
                null, null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 12L, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);
    }
}
