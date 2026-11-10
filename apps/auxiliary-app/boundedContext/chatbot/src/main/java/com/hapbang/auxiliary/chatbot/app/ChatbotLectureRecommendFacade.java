package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendConfig;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

/**
 * 강의 추천 API 진입점 (#718). LLM 없이 후보만 준다(FE 추천 위젯).
 * 관심사를 넣은 요청만 회원별 호출 제한을 센다(임베딩 비용). DB 트랜잭션을 열지 않는다(ES·임베딩 호출 사이에 연결을 쥐지 않게,
 * 조회는 각각 짧게 끝난다).
 */
@Component
public class ChatbotLectureRecommendFacade {

    private final LectureRecommendUseCase recommendUseCase;
    private final KeyedRateLimiter rateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotLectureRecommendFacade(LectureRecommendUseCase recommendUseCase,
            @Qualifier(ChatbotLectureRecommendConfig.RATE_LIMITER) KeyedRateLimiter rateLimiter, ChatbotMetrics metrics) {
        this.recommendUseCase = recommendUseCase;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * @param interest 관심사(1~100자, 앞뒤 공백 제외). 없으면 null
     */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public LectureRecommendResDto recommend(long userId, @Nullable String interest) {
        String normalized = interest == null ? null : interest.strip();
        if (normalized != null) {
            try {
                rateLimiter.acquire(String.valueOf(userId));
            } catch (RateLimitExceededException e) {
                metrics.rateLimited(Feature.LECTURE_RECOMMEND);
                throw e;
            }
        }
        metrics.request(Feature.LECTURE_RECOMMEND);
        return LectureRecommendResDto.from(recommendUseCase.recommend(userId, normalized));
    }
}
