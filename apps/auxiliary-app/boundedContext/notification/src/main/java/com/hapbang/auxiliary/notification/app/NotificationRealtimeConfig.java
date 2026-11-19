package com.hapbang.auxiliary.notification.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 실시간 발행 설정(ND-226). 발행 설정값({@link NotificationRealtimePublishProperties})은 조건 없이 등록한다. 실시간 전달을 끈
 * 실행에서도 {@link NotificationRealtimeRelay}가 만들어지기 때문이다.
 * <p>
 * 실시간 발행 스레드({@link NotificationRealtimeWorker}, ND-205)는 실시간 전달을 켠 실행에만 만든다. 스레드 1개가 Relay의 줄(상한
 * {@code queue-capacity}, 기본 1000)을 묶음({@code batch-size}, 기본 100)으로 비운다. 줄이 차면 Relay가 그 프레임을 버린다.
 * 여러 저장의 프레임을 묶음으로 처리하므로 넘긴 쪽의 trace를 잇지 않는다. 묶음 안 다른 프레임의 로그에 틀린 {@code traceId}가 붙기
 * 때문이다(docs/dev/logging.md "스레드 경계").
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRealtimePublishProperties.class)
public class NotificationRealtimeConfig {

    @Bean
    @ConditionalOnNotificationRealtime
    NotificationRealtimeWorker notificationRealtimeWorker(NotificationRealtimeRelay relay) {
        return new NotificationRealtimeWorker(relay);
    }
}
