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
 * 강의 추천 API 진입점 (#718, #720). LLM 없이 후보만 준다(FE 추천 위젯).
 * <ul>
 *     <li>모든 요청을 넓은 회원별 제한으로 센다(연타가 인기순 집계·공용 DB 연결을 붙잡지 않게). 관심사를 넣은 요청은 임베딩 비용이
 *     있어 좁은 제한도 함께 센다. 둘 중 하나라도 넘으면 429다(FE는 위젯을 숨긴다).</li>
 *     <li>DB 트랜잭션을 열지 않는다(ES·임베딩 호출 사이에 연결을 쥐지 않게, 조회는 각각 짧게 끝난다).</li>
 * </ul>
 */
@Component
public class ChatbotLectureRecommendFacade {

    private final LectureRecommendUseCase recommendUseCase;
    private final LectureRecommendExplainUseCase explainUseCase;
    private final KeyedRateLimiter interestRateLimiter;
    private final KeyedRateLimiter buttonRateLimiter;
    private final KeyedRateLimiter widgetRateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotLectureRecommendFacade(LectureRecommendUseCase recommendUseCase, LectureRecommendExplainUseCase explainUseCase,
            @Qualifier(ChatbotLectureRecommendConfig.RATE_LIMITER) KeyedRateLimiter interestRateLimiter,
            @Qualifier(ChatbotLectureRecommendConfig.BUTTON_RATE_LIMITER) KeyedRateLimiter buttonRateLimiter,
            @Qualifier(ChatbotLectureRecommendConfig.WIDGET_RATE_LIMITER) KeyedRateLimiter widgetRateLimiter,
            ChatbotMetrics metrics) {
        this.recommendUseCase = recommendUseCase;
        this.explainUseCase = explainUseCase;
        this.interestRateLimiter = interestRateLimiter;
        this.buttonRateLimiter = buttonRateLimiter;
        this.widgetRateLimiter = widgetRateLimiter;
        this.metrics = metrics;
    }

    /**
     * @param interest 관심사(앞뒤 공백을 뺀 길이로 검증된 값). 없거나 공백뿐이면 null로 본다
     */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public LectureRecommendResDto recommend(long userId, @Nullable String interest) {
        String normalized = RecommendInterest.normalize(interest);
        acquire(widgetRateLimiter, userId, Feature.LECTURE_RECOMMEND);
        if (normalized != null) {
            acquire(interestRateLimiter, userId, Feature.LECTURE_RECOMMEND);
        }
        metrics.request(Feature.LECTURE_RECOMMEND);
        return LectureRecommendResDto.from(recommendUseCase.recommend(userId, normalized));
    }

    /**
     * 챗봇 "강의 추천받기" 버튼: 쿼리 확장 + 리랭킹 + 이유. LLM이 실패하면 코드 순서와 이유 코드 문장({@code fallback=true}).
     * 회원별 호출 제한은 위젯과 따로 센다(LLM 비용).
     */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public LectureRecommendResDto recommendExplained(long userId, @Nullable String interest) {
        acquire(buttonRateLimiter, userId, Feature.LECTURE_BUTTON);
        metrics.request(Feature.LECTURE_BUTTON);
        LectureRecommendExplainUseCase.Explained explained =
                explainUseCase.recommend(userId, RecommendInterest.normalize(interest));
        return LectureRecommendResDto.from(explained.recommendation(), explained.fallback());
    }

    private void acquire(KeyedRateLimiter limiter, long userId, Feature feature) {
        try {
            limiter.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            metrics.rateLimited(feature);
            throw e;
        }
    }
}
