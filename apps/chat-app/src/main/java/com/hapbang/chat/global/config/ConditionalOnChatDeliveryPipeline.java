package com.hapbang.chat.global.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.hapbang.chat.app.ChatDeliveryProperties;

/**
 * 전달 파이프라인 빈은 {@code hapbang.chat.delivery.pipeline.enabled=true}일 때만 만든다.
 * <p>
 * 운영 {@code RealtimeChatFanout} 구현이 합쳐지기 전에는 기본 false다({@code ChatDeliveryProperties.Pipeline}의 기본값과
 * 같다). 그 구현을 합칠 때 기본을 켠다. 파이프라인을 검증하는 테스트만 켠다. 주기 실행·worker 진입 빈은
 * {@code @ConditionalOnSchedulingEnabled}도 함께 붙여, 테스트는 처리 빈을 직접 호출해 검증한다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ChatDeliveryProperties.PIPELINE_ENABLED, havingValue = "true")
public @interface ConditionalOnChatDeliveryPipeline {
}
