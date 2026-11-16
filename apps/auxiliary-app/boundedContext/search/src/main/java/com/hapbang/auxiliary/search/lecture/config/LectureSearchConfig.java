package com.hapbang.auxiliary.search.lecture.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.search.lecture.app.LectureSearchRateLimit;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;

import io.micrometer.core.instrument.MeterRegistry;

/** 강의 검색 빈(#883): 회원·비회원 호출 제한. 검색어 자동완성(#973)은 같은 키 규칙으로 한도를 따로 둔다 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LectureRateLimitProperties.class, LectureSuggestionRateLimitProperties.class})
public class LectureSearchConfig {

    public static final String MEMBER_RATE_LIMITER = "lectureSearchMemberRateLimiter";
    public static final String GUEST_RATE_LIMITER = "lectureSearchGuestRateLimiter";
    public static final String SUGGESTION_MEMBER_RATE_LIMITER = "lectureSuggestionMemberRateLimiter";
    public static final String SUGGESTION_GUEST_RATE_LIMITER = "lectureSuggestionGuestRateLimiter";
    public static final String SUGGESTION_RATE_LIMIT = "lectureSuggestionRateLimit";

    /** 자동완성 지표 대상 값({@code hapbang.search.requests}·{@code rate.limit.*}의 {@code target}) */
    public static final String SUGGESTION_METRIC_TARGET = "lecture_suggestion";

    /** 회원 키는 회원 ID다 */
    @Bean(name = MEMBER_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSearchMemberRateLimiter(LectureRateLimitProperties properties) {
        LectureRateLimitProperties.Limit limit = properties.member();
        return new KeyedRateLimiter("search-lecture-member", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 비회원 키는 client IP의 HMAC({@code ClientIpKey})이라 거절 시작 WARN에도 IP가 남지 않는다 */
    @Bean(name = GUEST_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSearchGuestRateLimiter(LectureRateLimitProperties properties) {
        LectureRateLimitProperties.Limit limit = properties.guest();
        return new KeyedRateLimiter("search-lecture-guest", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 본 검색 호출 제한. 이름 없이 주입받는 기본 후보다 */
    @Bean
    LectureSearchRateLimit lectureSearchRateLimit(@Qualifier(MEMBER_RATE_LIMITER) KeyedRateLimiter member,
            @Qualifier(GUEST_RATE_LIMITER) KeyedRateLimiter guest, MeterRegistry registry) {
        return new LectureSearchRateLimit(member, guest, registry, LectureIndexDefinition.METRIC_TARGET);
    }

    @Bean(name = SUGGESTION_MEMBER_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSuggestionMemberRateLimiter(LectureSuggestionRateLimitProperties properties) {
        LectureSuggestionRateLimitProperties.Limit limit = properties.member();
        return new KeyedRateLimiter("search-lecture-suggestion-member", limit.limit(), limit.period(), limit.maxKeys());
    }

    @Bean(name = SUGGESTION_GUEST_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSuggestionGuestRateLimiter(LectureSuggestionRateLimitProperties properties) {
        LectureSuggestionRateLimitProperties.Limit limit = properties.guest();
        return new KeyedRateLimiter("search-lecture-suggestion-guest", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 자동완성 호출 제한. 본 검색과 섞이지 않게 이름으로만 주입받는다 */
    @Bean(name = SUGGESTION_RATE_LIMIT, defaultCandidate = false)
    LectureSearchRateLimit lectureSuggestionRateLimit(@Qualifier(SUGGESTION_MEMBER_RATE_LIMITER) KeyedRateLimiter member,
            @Qualifier(SUGGESTION_GUEST_RATE_LIMITER) KeyedRateLimiter guest, MeterRegistry registry) {
        return new LectureSearchRateLimit(member, guest, registry, SUGGESTION_METRIC_TARGET);
    }
}
