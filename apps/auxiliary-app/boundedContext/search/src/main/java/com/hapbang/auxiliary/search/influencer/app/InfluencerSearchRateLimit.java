package com.hapbang.auxiliary.search.influencer.app;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 인플루언서 검색 호출 제한(#1012). 한 회원이 몰아서 불러 ES가 있는 공용 node CPU를 채우지 않게 한다(#993 부하 측정).
 * 로그인 전용이라 회원 ID로만 센다(강의 검색의 비회원·IP 키가 없다). 본 검색(지표 대상 {@code influencer})과 검색어 자동완성
 * (대상 {@code influencer_suggestion})이 한도를 따로 갖도록 빈을 둘 만든다({@code InfluencerSearchConfig}).
 * <ul>
 *     <li>본 검색은 분당 60회, 자동완성은 분당 120회가 기본이다. 넘으면 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After}(global 처리기).
 *     거절은 요청마다 로그를 남기지 않는다({@code docs/dev/logging.md} 429).</li>
 * </ul>
 * 지표 {@code hapbang.search.rate.limit.rejected{target, requester=member}}: 429로 거절한 수(강의 검색과 같은 이름)
 */
public class InfluencerSearchRateLimit {

    private final KeyedRateLimiter member;
    private final Counter rejected;

    /**
     * @param target 지표 태그 값(본 검색 {@code influencer}, 자동완성 {@code influencer_suggestion})
     */
    public InfluencerSearchRateLimit(KeyedRateLimiter member, MeterRegistry registry, String target) {
        this.member = member;
        this.rejected = Counter.builder("hapbang.search.rate.limit.rejected").tag("target", target)
                .tag("requester", "member").description("검색 호출 제한으로 429를 준 수").register(registry);
    }

    /**
     * @param userId 부른 회원 ID
     * @throws RateLimitExceededException 한도를 넘었을 때
     */
    public void acquire(long userId) {
        try {
            member.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            rejected.increment();
            throw e;
        }
    }
}
