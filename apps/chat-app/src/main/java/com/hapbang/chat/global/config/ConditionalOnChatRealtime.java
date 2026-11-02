package com.hapbang.chat.global.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 실시간 전달(Redis Pub/Sub 발행·구독) 빈은 {@value #REALTIME_ENABLED}가 {@code false}가 아니면 만든다.
 * <p>
 * 기본값은 켜짐이다(속성이 없으면 켠 것으로 본다). 기본값은 이 조건이 원본이고 application.yaml에 두지 않는다(설정 위치 사용자 결정,
 * 2026-10-30). Redis 없이 도는 모듈 테스트는 테스트 설정({@code hapbang-module-test.yaml})에서 끄고, 실시간 경로를 검증하는
 * E2E 테스트만 켠다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ConditionalOnChatRealtime.REALTIME_ENABLED, havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnChatRealtime {

    /** 실시간 전달 사용 여부 속성 */
    String REALTIME_ENABLED = "hapbang.chat.realtime.enabled";
}
