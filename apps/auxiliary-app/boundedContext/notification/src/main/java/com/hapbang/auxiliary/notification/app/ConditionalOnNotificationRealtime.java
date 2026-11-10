package com.hapbang.auxiliary.notification.app;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 실시간 전달(웹소켓·Redis Pub/Sub·연결 티켓 저장소, ND-027·ND-028) 빈은 {@value #REALTIME_ENABLED}가 {@code false}가 아니면 만든다.
 * 기본값은 켜짐이다. Redis 없이 도는 모듈 테스트는 테스트 설정({@code hapbang-module-test.yaml})에서 끄고, 실시간 시험만 켠다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ConditionalOnNotificationRealtime.REALTIME_ENABLED, havingValue = "true",
        matchIfMissing = true)
public @interface ConditionalOnNotificationRealtime {

    /** 실시간 전달 사용 여부 속성 */
    String REALTIME_ENABLED = "hapbang.notification.realtime.enabled";
}
