package com.hapbang.auxiliary.notification.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실시간 발행 스레드({@link NotificationRealtimeWorker}, ND-205). 스레드 1개가 {@link NotificationRealtimeRelay}의 줄(상한
 * {@code queue-capacity}, 기본 1000)을 묶음({@code batch-size}, 기본 100)으로 비운다. 줄이 차면 Relay가 그 프레임을 버린다.
 * <p>
 * 여러 저장의 프레임을 묶음으로 처리하므로 넘긴 쪽의 trace를 잇지 않는다. 묶음 안 다른 프레임의 로그에 틀린 {@code traceId}가 붙기
 * 때문이다(docs/dev/logging.md "스레드 경계"). 실시간 전달을 끈 실행에서는 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotificationRealtime
public class NotificationRealtimeExecutorConfig {

    @Bean
    NotificationRealtimeWorker notificationRealtimeWorker(NotificationRealtimeRelay relay) {
        return new NotificationRealtimeWorker(relay);
    }
}
