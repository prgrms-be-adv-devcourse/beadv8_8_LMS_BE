package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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

    @Test
    void 멈추면_새_payload는_버리고_처리_중인_작업이_끝날_때까지_기다릴_수_있고_다시_시작하면_받는다() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(facade).deliverMessage(any());
        MessageFanout inFlight = fanout();
        publish(inFlight);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        listener.pause();
        MessageFanout whilePaused = fanout();
        publish(whilePaused);
        assertThat(listener.awaitIdle(Duration.ofMillis(200))).as("처리 중이면 비지 않았다").isFalse();
        release.countDown();
        assertThat(listener.awaitIdle(Duration.ofSeconds(2))).isTrue();

        verify(facade).deliverMessage(inFlight);
        verify(facade, never()).deliverMessage(whilePaused);
        verify(facade, never()).onMessageDropped(anyLong());

        listener.resume();
        MessageFanout afterResume = fanout();
        publish(afterResume);
        verify(facade, timeout(2_000)).deliverMessage(afterResume);
    }

    private void publish(MessageFanout fanout) {
        listener.onMessage(new DefaultMessage(keys.roomChannel(12).getBytes(StandardCharsets.UTF_8),
                jsonMapper.writeValueAsBytes(fanout)), null);
    }

    private static MessageFanout fanout() {
        ChatMessageResponse message = new ChatMessageResponse(1L, 12L, 1L, 3L, ChatMessageType.TEXT, "안녕",
                null, null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 12L, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);
    }
}
