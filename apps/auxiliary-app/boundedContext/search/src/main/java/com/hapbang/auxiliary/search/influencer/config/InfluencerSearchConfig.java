package com.hapbang.auxiliary.search.influencer.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.search.influencer.app.InfluencerSearchRateLimit;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;

import io.micrometer.core.instrument.MeterRegistry;

/** 인플루언서 검색 빈(#1012): 회원 호출 제한. 검색어 자동완성은 같은 키 규칙(회원 ID)으로 한도를 따로 둔다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InfluencerRateLimitProperties.class, InfluencerSuggestionRateLimitProperties.class})
public class InfluencerSearchConfig {

    public static final String MEMBER_RATE_LIMITER = "influencerSearchMemberRateLimiter";
    public static final String SUGGESTION_MEMBER_RATE_LIMITER = "influencerSuggestionMemberRateLimiter";
    public static final String SUGGESTION_RATE_LIMIT = "influencerSuggestionRateLimit";

    /** 자동완성 지표 대상 값({@code hapbang.search.requests}·{@code rate.limit.rejected}의 {@code target}) */
    public static final String SUGGESTION_METRIC_TARGET = "influencer_suggestion";

    @Bean(name = MEMBER_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter influencerSearchMemberRateLimiter(InfluencerRateLimitProperties properties) {
        InfluencerRateLimitProperties.Limit limit = properties.member();
        return new KeyedRateLimiter("search-influencer-member", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 본 검색 호출 제한. 이름 없이 주입받는 기본 후보다 */
    @Bean
    InfluencerSearchRateLimit influencerSearchRateLimit(@Qualifier(MEMBER_RATE_LIMITER) KeyedRateLimiter member,
            MeterRegistry registry) {
        return new InfluencerSearchRateLimit(member, registry, InfluencerIndexDefinition.METRIC_TARGET);
    }

    @Bean(name = SUGGESTION_MEMBER_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter influencerSuggestionMemberRateLimiter(InfluencerSuggestionRateLimitProperties properties) {
        InfluencerSuggestionRateLimitProperties.Limit limit = properties.member();
        return new KeyedRateLimiter("search-influencer-suggestion-member", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 자동완성 호출 제한. 본 검색과 섞이지 않게 이름으로만 주입받는다 */
    @Bean(name = SUGGESTION_RATE_LIMIT, defaultCandidate = false)
    InfluencerSearchRateLimit influencerSuggestionRateLimit(
            @Qualifier(SUGGESTION_MEMBER_RATE_LIMITER) KeyedRateLimiter member, MeterRegistry registry) {
        return new InfluencerSearchRateLimit(member, registry, SUGGESTION_METRIC_TARGET);
    }
}
