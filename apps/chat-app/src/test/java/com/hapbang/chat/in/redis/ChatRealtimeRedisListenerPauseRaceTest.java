package com.hapbang.chat.in.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.global.redis.ChatRedisKeys;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * 종료(destroy)로 받기를 멈춘 뒤의 재구독 알림: 재동기화·재검사도 처리 작업과 같은 받기 잠금을 지나므로 예약하지 않는다.
 */
class ChatRealtimeRedisListenerPauseRaceTest {

    private final ChatRedisKeys keys = new ChatRedisKeys("test-pause-race");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 종료한_뒤의_재구독_알림은_재동기화와_재검사를_예약하지_않는다() {
        ChatRealtimeFacade facade = mock(ChatRealtimeFacade.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ChatRealtimeRedisListener listener = new ChatRealtimeRedisListener(facade, jsonMapper, keys, 1, 10, 1, 10,
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
