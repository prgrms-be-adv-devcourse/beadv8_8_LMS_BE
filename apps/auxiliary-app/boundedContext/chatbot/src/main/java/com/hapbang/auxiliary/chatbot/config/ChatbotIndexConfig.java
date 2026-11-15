package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 추천 벡터 index·색인 요청 실행기 설정(#718 공통 기반) */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotIndexProperties.class)
public class ChatbotIndexConfig {
}
