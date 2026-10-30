package com.hapbang.chat.out.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.MessageListener;

import com.hapbang.chat.global.config.ConditionalOnChatRealtime;

/**
 * 채팅 실시간 경로의 Redis Pub/Sub 구독. 서버 채널(알림 재료·제어)은 늘 구독하고, 방 채널은 그 방을 연 로컬 세션이 있을 때만
 * 구독한다. 모든 채널을 한 구독 연결로 받는다(설계 4.2).
 * <ul>
 *     <li>구독 진입 어댑터({@code in/redis}의 {@value #LISTENER_BEAN_NAME})는 {@link MessageListener}이자
 *     {@link SubscriptionListener}다. 구독 확인 알림으로 끊김 뒤 재구독을 알아낸다.</li>
 *     <li>컨테이너의 분배 실행기는 호출 스레드(Lettuce I/O 스레드)에서 바로 진입 어댑터를 부른다. 진입 어댑터는 payload를
 *     자기의 제한된 실행기에 넘기기만 하고 막지 않는다. 그래서 메시지 폭주가 제어 명령을 굶기지 않도록 실행기를 나눌 수 있다.</li>
 *     <li>Redis가 끊기면 Lettuce가 다시 연결하며 채널을 다시 구독한다. 컨테이너의 구독 재시도 간격은
 *     {@code hapbang.chat.realtime.listener.recovery-interval}이다.</li>
 * </ul>
 * {@link ConditionalOnChatRealtime}(기본 켜짐)일 때만 등록한다. Redis 없이 도는 모듈 테스트는 테스트 설정에서 끈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnChatRealtime
public class ChatRealtimeRedisConfig {

    /** 구독 진입 어댑터 빈 이름. {@code in/redis}의 어댑터가 이 이름으로 등록한다 */
    public static final String LISTENER_BEAN_NAME = "chatRealtimeRedisListener";
    /** 구독 컨테이너 빈 이름. 방 채널 동적 구독이 이 이름으로 주입받는다(다른 모듈의 컨테이너와 구분한다) */
    public static final String CONTAINER_BEAN_NAME = "chatRealtimeListenerContainer";

    /** 구독 재시도 간격 기본 5s는 미확정 초기값(Q12)이다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다 */
    @Bean
    RedisMessageListenerContainer chatRealtimeListenerContainer(RedisConnectionFactory connectionFactory,
            ChatRedisKeys chatRedisKeys, @Qualifier(LISTENER_BEAN_NAME) MessageListener listener,
            @Value("${hapbang.chat.realtime.listener.recovery-interval:5s}") Duration recoveryInterval) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.setRecoveryInterval(recoveryInterval.toMillis());
        // 서버 채널은 늘 구독한다. 방 채널은 RedisRoomChannelSubscriptions가 실행 중에 더하고 뺀다
        container.addMessageListener(listener, serverTopics(chatRedisKeys));
        return container;
    }

    /** 늘 구독하는 서버 채널(알림 재료·제어). 방 채널 구독을 재설정할 때도 이 목록으로 다시 등록한다 */
    static List<ChannelTopic> serverTopics(ChatRedisKeys chatRedisKeys) {
        return List.of(new ChannelTopic(chatRedisKeys.activityChannel()),
                new ChannelTopic(chatRedisKeys.controlChannel()));
    }
}
