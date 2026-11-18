package com.hapbang.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 기존 회원 백필 설정 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatUserBackfillProperties.class)
public class ChatUserBackfillConfig {
}
