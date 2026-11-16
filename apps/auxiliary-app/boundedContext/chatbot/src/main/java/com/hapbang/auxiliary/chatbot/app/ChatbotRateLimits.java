package com.hapbang.auxiliary.chatbot.app;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

/** Facade들의 회원별 호출 제한. 회원 ID를 키로 한 번 세고, 넘으면 거절 지표를 남기고 429 예외를 그대로 던진다 */
final class ChatbotRateLimits {

    private ChatbotRateLimits() {
    }

    static void acquire(KeyedRateLimiter limiter, long userId, Feature feature, ChatbotMetrics metrics) {
        try {
            limiter.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            metrics.rateLimited(feature);
            throw e;
        }
    }
}
