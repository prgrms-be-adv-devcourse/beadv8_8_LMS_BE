package com.hapbang.auxiliary.search.lecture.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

/** 강의 검색 빈(#883): 회원·비회원 호출 제한 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LectureRateLimitProperties.class)
public class LectureSearchConfig {

    public static final String MEMBER_RATE_LIMITER = "lectureSearchMemberRateLimiter";
    public static final String GUEST_RATE_LIMITER = "lectureSearchGuestRateLimiter";

    /** 회원 키는 회원 ID다 */
    @Bean(name = MEMBER_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSearchMemberRateLimiter(LectureRateLimitProperties properties) {
        LectureRateLimitProperties.Limit limit = properties.member();
        return new KeyedRateLimiter("search-lecture-member", limit.limit(), limit.period(), limit.maxKeys());
    }

    /** 비회원 키는 client IP의 HMAC({@code ClientIpKey})이라 거절 시작 WARN에도 IP가 남지 않는다(Q5) */
    @Bean(name = GUEST_RATE_LIMITER, defaultCandidate = false)
    KeyedRateLimiter lectureSearchGuestRateLimiter(LectureRateLimitProperties properties) {
        LectureRateLimitProperties.Limit limit = properties.guest();
        return new KeyedRateLimiter("search-lecture-guest", limit.limit(), limit.period(), limit.maxKeys());
    }
}
