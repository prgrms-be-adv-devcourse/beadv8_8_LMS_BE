package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

/** FAQ 챗봇 빈(#715). LLM 회로는 {@link ChatbotLlmConfig} */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotFaqProperties.class)
public class ChatbotFaqConfig {

    /** FAQ 검색·대화 회원별 호출 제한. 키는 회원 ID다(챗봇은 로그인 회원만 쓴다) */
    @Bean
    KeyedRateLimiter chatbotFaqRateLimiter(ChatbotFaqProperties properties) {
        ChatbotFaqProperties.RateLimit limit = properties.rateLimit();
        return new KeyedRateLimiter("chatbot-faq", limit.limit(), limit.period(), limit.maxKeys());
    }
}
