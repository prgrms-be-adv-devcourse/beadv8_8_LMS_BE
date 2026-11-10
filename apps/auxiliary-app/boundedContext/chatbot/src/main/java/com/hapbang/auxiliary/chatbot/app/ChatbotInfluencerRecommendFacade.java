package com.hapbang.auxiliary.chatbot.app;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendExplainer.Explained;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendConfig;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

/**
 * 인플루언서 추천 API의 진입점 (#719). 역할 검사·회원별 호출 제한·사용 지표를 여기서 한다.
 * <ul>
 *     <li>로그인 회원 전체(USER 이상). 비회원은 공통 SecurityConfig가 401로 막는다.</li>
 *     <li>호출 제한은 LLM을 부르는 설명 경로({@code /explained})에만 건다. 위젯 경로는 사본·ES만 읽는다.</li>
 *     <li>트랜잭션을 열지 않는다. 사본 읽기 사이에 ES·LLM 호출이 있다(챗봇 규칙: 외부 호출 중 DB 트랜잭션을 열어 두지 않는다).</li>
 * </ul>
 */
@Service
public class ChatbotInfluencerRecommendFacade {

    private final InfluencerRecommendUseCase useCase;
    private final InfluencerRecommendExplainer explainer;
    private final InfluencerRecommendMapper mapper;
    private final KeyedRateLimiter rateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotInfluencerRecommendFacade(InfluencerRecommendUseCase useCase, InfluencerRecommendExplainer explainer,
            InfluencerRecommendMapper mapper,
            @Qualifier(ChatbotInfluencerRecommendConfig.RATE_LIMITER) KeyedRateLimiter rateLimiter, ChatbotMetrics metrics) {
        this.useCase = useCase;
        this.explainer = explainer;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /** 후보만(LLM 없음, FE 추천 위젯) */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public InfluencerRecommendResDto recommend(long userId) {
        return mapper.response(candidates(userId), null, false);
    }

    /** 후보 + LLM 이유(챗봇 "인플루언서 추천받기" 버튼). LLM이 실패하면 이유 코드 문장 */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public InfluencerRecommendResDto recommendExplained(long userId) {
        try {
            rateLimiter.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            metrics.rateLimited(Feature.INFLUENCER_RECOMMEND);
            throw e;
        }
        InfluencerRecommendation recommendation = candidates(userId);
        Explained explained = explainer.explain(recommendation.items());
        if (explained.fallback() != null) {
            metrics.fallback(Feature.INFLUENCER_RECOMMEND, explained.fallback());
        }
        return mapper.response(recommendation, explained.explanations(), explained.fallback() != null);
    }

    // 버튼·위젯 경로는 관심사 문장을 받지 않는다(관심사는 자유 질문 Tool만)
    private InfluencerRecommendation candidates(long userId) {
        metrics.request(Feature.INFLUENCER_RECOMMEND);
        return useCase.recommend(userId, null);
    }
}
