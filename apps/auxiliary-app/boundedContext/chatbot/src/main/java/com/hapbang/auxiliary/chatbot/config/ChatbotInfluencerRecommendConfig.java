package com.hapbang.auxiliary.chatbot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

/** 인플루언서 추천 빈(#719). 강의 추천과 설정·호출 제한을 따로 둔다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotInfluencerRecommendProperties.class)
public class ChatbotInfluencerRecommendConfig {

    /** 호출 제한 빈 이름 */
    public static final String RATE_LIMITER = "chatbotInfluencerRecommendRateLimiter";

    /** LLM 설명 버튼 경로의 회원별 호출 제한. 키는 회원 ID다 */
    @Bean(RATE_LIMITER)
    KeyedRateLimiter chatbotInfluencerRecommendRateLimiter(ChatbotInfluencerRecommendProperties properties) {
        ChatbotInfluencerRecommendProperties.RateLimit limit = properties.rateLimit();
        return new KeyedRateLimiter("chatbot-recommend-influencer", limit.limit(), limit.period(), limit.maxKeys());
    }
}
