package com.hapbang.auxiliary.notification.app;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 실시간 발행 실행기({@link NotificationRealtimeRelay}). 스레드 1개, 줄 상한 {@code queue-capacity}(기본 1000). 작업 하나가 저장 하나의
 * 발행이라 Boot builder로 만들어 넘긴 쪽의 trace를 잇는다(docs/dev/logging.md). 줄이 차면 넘기기가 실패하고 Relay가 그 프레임을 버린다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotificationRealtime
public class NotificationRealtimeExecutorConfig {

    @Bean(name = NotificationRealtimeRelay.EXECUTOR_BEAN_NAME)
    ThreadPoolTaskExecutor notificationRealtimeExecutor(ThreadPoolTaskExecutorBuilder builder,
            @Value("${hapbang.notification.realtime.publish.queue-capacity:1000}") int queueCapacity) {
        return builder
                .corePoolSize(1)
                .maxPoolSize(1)
                .queueCapacity(queueCapacity)
                .allowCoreThreadTimeOut(false)
                .keepAlive(Duration.ofSeconds(60))
                .acceptTasksAfterContextClose(false)
                .awaitTermination(false)
                .threadNamePrefix("notification-realtime-")
                .build();
    }
}
