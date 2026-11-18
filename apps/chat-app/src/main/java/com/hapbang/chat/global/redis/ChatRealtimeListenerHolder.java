package com.hapbang.chat.global.redis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 채팅 실시간 채널의 구독 대상(구독 진입 어댑터, {@code in/redis})을 구독 컨테이너와 방 채널 구독({@code out/redis})에 잇는다.
 * <ul>
 *     <li>구독 진입 어댑터는 만들어질 때 {@link #attach}로 자기를 넣는다. 그때 서버 채널을 컨테이너에 등록한다. 컨테이너는 모든
 *     싱글턴을 만든 뒤 생명주기 시작에서야 구독하므로, 시작할 때 서버 채널은 이미 등록돼 있다.</li>
 *     <li>방 채널 구독은 방 채널을 더하고 빼거나 재설정할 때 {@link #listener()}로 대상을 얻는다. 방 구독은 STOMP 구독으로 생기므로
 *     앱이 뜬 뒤에만 부른다.</li>
 * </ul>
 * 구독 진입 어댑터는 Facade를 거쳐 app을 쓰고, app의 방 구독 목록은 방 채널 구독을 쓴다. 방 채널 구독이 어댑터를 생성자로 받으면
 * 생성 순환이 되므로, 어댑터가 실행 중에 자기를 넣는 이 자리로 잇는다. 이 빈은 컨테이너와 키에만 기댄다.
 */
@Component
public class ChatRealtimeListenerHolder {

    private final RedisMessageListenerContainer container;
    private final ChatRedisKeys chatRedisKeys;
    private volatile MessageListener listener;

    public ChatRealtimeListenerHolder(
            @Qualifier(ChatRealtimeRedisConfig.CONTAINER_BEAN_NAME) RedisMessageListenerContainer container,
            ChatRedisKeys chatRedisKeys) {
        this.container = container;
        this.chatRedisKeys = chatRedisKeys;
    }

    /** 구독 진입 어댑터를 넣고 서버 채널을 컨테이너에 등록한다. 한 번만 부른다 */
    public synchronized void attach(MessageListener listener) {
        Assert.notNull(listener, "listener가 필요합니다.");
        Assert.state(this.listener == null, "실시간 채널 구독 대상이 이미 있습니다.");
        container.addMessageListener(listener, ChatRealtimeRedisConfig.serverTopics(chatRedisKeys));
        this.listener = listener;
    }

    /**
     * 넣어 둔 구독 진입 어댑터
     *
     * @throws IllegalStateException 아직 넣지 않았을 때(앱 시작 전)
     */
    public MessageListener listener() {
        MessageListener current = listener;
        Assert.state(current != null, "실시간 채널 구독 대상이 아직 없습니다.");
        return current;
    }
}
