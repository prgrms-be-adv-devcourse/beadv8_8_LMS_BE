package com.hapbang.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 단체방 설정 등록 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatGroupProperties.class)
public class ChatGroupConfig {
}
