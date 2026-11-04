package com.hapbang.chat.global.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.hapbang.chat.app.ChatDeliveryProperties;

/**
 * 전달 파이프라인 빈은 {@code hapbang.chat.delivery.pipeline.enabled}가 {@code false}가 아니면 만든다.
 * <p>
 * 기본은 켜짐이다(속성이 없으면 켠 것으로 본다. {@code ChatDeliveryProperties.Pipeline}의 기본값과 같다). 운영
 * {@code RealtimeChatFanout} 구현과 합친 통합에서 켰다. 모듈 테스트 설정은 끄고, 파이프라인을 검증하는 테스트만 켠다. 주기 실행·worker 진입 빈은
 * {@code @ConditionalOnSchedulingEnabled}도 함께 붙여, 테스트는 처리 빈을 직접 호출해 검증한다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ChatDeliveryProperties.PIPELINE_ENABLED, havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnChatDeliveryPipeline {
}
