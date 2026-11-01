package com.hapbang.chat.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.hapbang.chat.app.ChatDeliveryProperties;

/** 전달 파이프라인 설정 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatDeliveryProperties.class)
public class ChatDeliveryConfig {
}
