package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.global.redis.ChatRedisKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 실행기가 넘쳐 전달 작업을 버리면, 그 작업만 제어 실행기에서 DELIVERY_DROPPED 재동기화로 넘기고 지표를 센다.
 */
class ChatRealtimeRedisListenerOverflowTest {

    private final ChatRedisKeys keys = new ChatRedisKeys("test-listener-overflow");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    // 메시지 실행기: 스레드 1개 + 대기열 1개. 세 번째 작업이 넘친다
    private final ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, 1, 1,
            1, 10, Duration.ofSeconds(1), meterRegistry);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        listener.destroy();
    }

    @Test
    void 메시지_실행기가_넘쳐_버린_작업만_DELIVERY_DROPPED_재동기화로_넘기고_지표를_센다() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(facade).deliverMessage(any());
        MessageFanout first = fanout();
        MessageFanout queued = fanout();
        MessageFanout dropped = fanout();

        publish(first);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        publish(queued);
        publish(dropped);

        verify(facade, timeout(2_000)).onMessageDropped(12L);
        assertThat(meterRegistry.counter(ChatRealtimeRedisListener.DROPPED_METRIC, "queue", "message").count())
                .isEqualTo(1);

        release.countDown();
        verify(facade, timeout(2_000)).deliverMessage(first);
        verify(facade, timeout(2_000)).deliverMessage(queued);
        verify(facade, after(300).times(1)).onMessageDropped(anyLong());
        verify(facade, never()).deliverMessage(dropped);
        assertThat(meterRegistry.counter(ChatRealtimeRedisListener.DROPPED_METRIC, "queue", "control").count())
                .isZero();
    }

    /**
     * 종료(destroy)는 새 payload 받기를 멈추고 처리 중인 작업이 끝날 때까지 기다린다. 멈춘 뒤 받은 payload는 처리하지 않고 버린다
     * (대기열이 넘친 것이 아니므로 재동기화로 넘기지 않는다).
     */
    @Test
    void 종료하면_새_payload는_버리고_처리_중인_작업이_끝날_때까지_기다린다() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        AtomicLong finishedAt = new AtomicLong();
        doAnswer(invocation -> {
            running.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            finishedAt.set(System.nanoTime());
            return null;
        }).when(facade).deliverMessage(any());
        MessageFanout inFlight = fanout();
        publish(inFlight);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicLong destroyedAt = new AtomicLong();
        Thread destroyer = new Thread(() -> {
            listener.destroy();
            destroyedAt.set(System.nanoTime());
        });
        destroyer.start();
        // 받기를 멈춘 뒤 처리 중인 작업을 기다리며 잠깐씩 잔다
        await().atMost(Duration.ofSeconds(2)).until(() -> destroyer.getState() == Thread.State.TIMED_WAITING);
        MessageFanout afterPause = fanout();
        publish(afterPause);
        assertThat(destroyer.isAlive()).as("처리 중인 작업을 기다린다").isTrue();

        release.countDown();
        destroyer.join(5_000);

        assertThat(destroyedAt.get()).as("작업이 끝난 뒤 종료").isGreaterThan(finishedAt.get());
        verify(facade).deliverMessage(inFlight);
        verify(facade, after(300).never()).deliverMessage(afterPause);
        verify(facade, never()).onMessageDropped(anyLong());
        assertThat(meterRegistry.counter(ChatRealtimeRedisListener.DROPPED_METRIC, "queue", "message").count())
                .isZero();
    }

    private void publish(MessageFanout fanout) {
        listener.onMessage(new DefaultMessage(keys.roomChannel(12).getBytes(StandardCharsets.UTF_8),
                jsonMapper.writeValueAsBytes(fanout)), null);
    }

    private static MessageFanout fanout() {
        ChatMessageResponse message = new ChatMessageResponse(1L, 12L, 1L, 3L, ChatMessageType.TEXT, "안녕",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 12L, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);
    }
}
