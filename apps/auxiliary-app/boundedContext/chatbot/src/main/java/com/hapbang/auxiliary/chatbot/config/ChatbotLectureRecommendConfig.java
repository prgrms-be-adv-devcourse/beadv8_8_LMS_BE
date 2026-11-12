package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

/**
 * 강의 추천 설정과 회원별 호출 제한 (#718, #720). 제한기는 FAQ와 이름을 나눈다. 위젯은 모든 요청을 넓은 제한으로 세고,
 * 관심사를 넣은 요청(임베딩 비용)은 좁은 제한도 함께 센다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotLectureRecommendProperties.class)
public class ChatbotLectureRecommendConfig {

    public static final String RATE_LIMITER = "chatbotLectureRecommendRateLimiter";
    public static final String WIDGET_RATE_LIMITER = "chatbotLectureWidgetRateLimiter";

    @Bean(RATE_LIMITER)
    KeyedRateLimiter chatbotLectureRecommendRateLimiter(ChatbotLectureRecommendProperties properties) {
        ChatbotLectureRecommendProperties.RateLimit limit = properties.rateLimit();
        return new KeyedRateLimiter("chatbot-recommend-lecture", limit.limit(), limit.period(), limit.maxKeys());
    }

    @Bean(WIDGET_RATE_LIMITER)
    KeyedRateLimiter chatbotLectureWidgetRateLimiter(ChatbotLectureRecommendProperties properties) {
        ChatbotLectureRecommendProperties.WidgetRateLimit limit = properties.widgetRateLimit();
        return new KeyedRateLimiter("chatbot-recommend-lecture-widget", limit.limit(), limit.period(), limit.maxKeys());
    }
}
