package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendExplainUseCase.Explained;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendConfig;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

/**
 * 인플루언서 추천 API의 진입점 (#719). 역할 검사·회원별 호출 제한·사용 지표를 여기서 한다.
 * <ul>
 *     <li>로그인 회원 전체(USER 이상). 비회원은 공통 SecurityConfig가 401로 막는다.</li>
 *     <li>호출 제한은 경로마다 따로 건다. 설명 경로({@code /explained})는 LLM 비용 때문에 좁게, 위젯 경로는 LLM이 없지만
 *     요청마다 추천 가능 회원 전체의 인기를 집계하므로(공유 DB 연결) 연타만 막도록 넓게.</li>
 *     <li>트랜잭션을 열지 않는다. 사본 읽기 사이에 ES·LLM 호출이 있다(챗봇 규칙: 외부 호출 중 DB 트랜잭션을 열어 두지 않는다).</li>
 * </ul>
 */
@Service
public class ChatbotInfluencerRecommendFacade {

    private final InfluencerRecommendUseCase useCase;
    private final InfluencerRecommendExplainUseCase explainUseCase;
    private final InfluencerRecommendMapper mapper;
    private final KeyedRateLimiter rateLimiter;
    private final KeyedRateLimiter widgetRateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotInfluencerRecommendFacade(InfluencerRecommendUseCase useCase, InfluencerRecommendExplainUseCase explainUseCase,
            InfluencerRecommendMapper mapper,
            @Qualifier(ChatbotInfluencerRecommendConfig.RATE_LIMITER) KeyedRateLimiter rateLimiter,
            @Qualifier(ChatbotInfluencerRecommendConfig.WIDGET_RATE_LIMITER) KeyedRateLimiter widgetRateLimiter,
            ChatbotMetrics metrics) {
        this.useCase = useCase;
        this.explainUseCase = explainUseCase;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.widgetRateLimiter = widgetRateLimiter;
        this.metrics = metrics;
    }

    /** 후보만(LLM 없음, FE 추천 위젯) */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public InfluencerRecommendResDto recommend(long userId) {
        acquire(widgetRateLimiter, userId, Feature.INFLUENCER_RECOMMEND);
        metrics.request(Feature.INFLUENCER_RECOMMEND);
        return mapper.response(candidates(userId));
    }

    /**
     * 챗봇 "인플루언서 추천받기" 버튼: 쿼리 확장 + 리랭킹 + 이유. LLM이 실패하면 코드 순서와 이유 코드 문장({@code fallback=true}).
     *
     * @param interest 관심사(검증된 값). 없거나 공백뿐이면 null로 본다
     */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public InfluencerRecommendResDto recommendExplained(long userId, @Nullable String interest) {
        acquire(rateLimiter, userId, Feature.INFLUENCER_BUTTON);
        metrics.request(Feature.INFLUENCER_BUTTON);
        Explained explained = explainUseCase.recommend(userId, RecommendInterest.normalize(interest));
        return mapper.explained(explained.recommendation(), explained.explanations(), explained.fallback());
    }

    private void acquire(KeyedRateLimiter limiter, long userId, Feature feature) {
        try {
            limiter.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            metrics.rateLimited(feature);
            throw e;
        }
    }

    // 위젯은 관심사를 받지 않는다(관심사는 버튼·자유 질문 Tool)
    private InfluencerRecommendation candidates(long userId) {
        return useCase.recommend(userId, null);
    }
}
