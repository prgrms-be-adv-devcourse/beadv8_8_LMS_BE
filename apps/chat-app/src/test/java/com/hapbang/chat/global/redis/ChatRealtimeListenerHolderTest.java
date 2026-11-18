package com.hapbang.chat.global.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 구독 진입 어댑터는 자기를 한 번 넣으며 서버 채널을 등록하고, 방 채널 구독은 넣은 뒤에만 대상을 얻는다 */
class ChatRealtimeListenerHolderTest {

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final ChatRedisKeys keys = new ChatRedisKeys("holder-test");
    private final ChatRealtimeListenerHolder holder = new ChatRealtimeListenerHolder(container, keys);
    private final MessageListener listener = mock(MessageListener.class);

    @Test
    void 넣으면_서버_채널을_컨테이너에_등록하고_대상을_돌려준다() {
        holder.attach(listener);

        verify(container).addMessageListener(listener, ChatRealtimeRedisConfig.serverTopics(keys));
        assertThat(holder.listener()).isSameAs(listener);
    }

    @Test
    void 넣기_전에는_대상이_없고_두_번_넣을_수_없다() {
        assertThatIllegalStateException().isThrownBy(holder::listener);

        holder.attach(listener);

        assertThatIllegalStateException().isThrownBy(() -> holder.attach(mock(MessageListener.class)));
        verify(container, times(1)).addMessageListener(any(MessageListener.class), anyCollection());
    }
}
