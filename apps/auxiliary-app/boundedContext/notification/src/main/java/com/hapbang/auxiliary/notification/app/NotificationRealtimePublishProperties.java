package com.hapbang.auxiliary.notification.app;

import java.time.Duration;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 실시간 발행 설정 {@code hapbang.notification.realtime.publish}(ND-205·ND-226). {@link NotificationRealtimeRelay}가 쓴다. 실시간
 * 전달을 끈 실행에서도 Relay가 만들어지므로 조건 없이 등록한다({@link NotificationRealtimeConfig}).
 *
 * @param failureBackoff 발행이 실패한 뒤 발행을 생략하는 시간
 * @param queueCapacity  commit된 뒤 발행을 기다리는 프레임 줄의 상한. 차면 그 프레임을 버린다({@code queue_full})
 * @param batchSize      발행 스레드가 한 번에 꺼내는 프레임 수
 */
@Validated
@ConfigurationProperties("hapbang.notification.realtime.publish")
public record NotificationRealtimePublishProperties(
        @DefaultValue("5s") Duration failureBackoff,
        @DefaultValue("1000") @Positive int queueCapacity,
        @DefaultValue("100") @Positive int batchSize) {
}
