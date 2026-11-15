package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

/** 강의 추천 설정과 관심사 요청의 회원별 호출 제한 (#718). 제한기는 FAQ와 이름을 나눈다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotLectureRecommendProperties.class)
public class ChatbotLectureRecommendConfig {

    public static final String RATE_LIMITER = "chatbotLectureRecommendRateLimiter";

    @Bean(RATE_LIMITER)
    KeyedRateLimiter chatbotLectureRecommendRateLimiter(ChatbotLectureRecommendProperties properties) {
        ChatbotLectureRecommendProperties.RateLimit limit = properties.rateLimit();
        return new KeyedRateLimiter("chatbot-recommend-lecture", limit.limit(), limit.period(), limit.maxKeys());
    }
}
