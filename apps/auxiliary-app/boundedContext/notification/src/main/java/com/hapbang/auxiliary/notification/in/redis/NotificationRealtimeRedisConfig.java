package com.hapbang.auxiliary.notification.in.redis;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.hapbang.auxiliary.notification.app.ConditionalOnNotificationRealtime;
import com.hapbang.auxiliary.notification.out.redis.NotificationRedisKeys;

/**
 * 알림 실시간 채널 구독(ND-027). 구독 연결 하나로 채널 하나를 받는다. 받은 작업은 구독 스레드에서 바로 메모리 브로커에 넘긴다
 * (브로커가 송신 채널 실행기로 보내므로 막지 않는다). Redis가 끊기면 Lettuce가 다시 연결하고 {@code recovery-interval}마다 다시 구독한다.
 * 끊긴 동안의 프레임은 유실되고 FE는 REST로 맞춘다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotificationRealtime
public class NotificationRealtimeRedisConfig {

    @Bean
    RedisMessageListenerContainer notificationRealtimeListenerContainer(RedisConnectionFactory connectionFactory,
            NotificationRedisKeys keys, @Qualifier(NotificationRealtimeRedisListener.BEAN_NAME) MessageListener listener,
            @Value("${hapbang.notification.realtime.listener.recovery-interval:5s}") Duration recoveryInterval) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.setRecoveryInterval(recoveryInterval.toMillis());
        container.addMessageListener(listener, new ChannelTopic(keys.realtimeChannel()));
        return container;
    }
}
