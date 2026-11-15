package com.hapbang.auxiliary.search.lecture.app;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;
import com.hapbang.auxiliary.search.lecture.config.LectureSearchConfig;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 검색 호출 제한(#883 L2·L18). 강의 검색은 비회원에게도 열려 있어, 로그인 없이 부르는 ES를 지킨다(인플루언서 검색은 로그인 전용이라 없다).
 * <ul>
 *     <li>회원은 회원 ID, 비회원은 client IP 키로 따로 센다(각 분당 60회 기본). 넘으면 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After}
 *     (global 처리기). 거절은 요청마다 로그를 남기지 않는다({@code docs/dev/logging.md} 429).</li>
 *     <li>IP를 믿을 수 없는 비회원(공인 주소가 아님)은 IP 한도를 건너뛴다. 한 사람이 모든 비회원을 막지 않게 하려는 것이고, 건수는 지표로 본다.</li>
 * </ul>
 * 지표
 * <ul>
 *     <li>{@code hapbang.search.rate.limit.rejected{target, requester}}: 429로 거절한 수. requester = member·guest</li>
 *     <li>{@code hapbang.search.rate.limit.skipped{target}}: 공인 주소가 아니라 IP 한도를 건너뛴 비회원 요청 수. 0이 아니면 Envoy 앞 경로를 확인한다</li>
 * </ul>
 */
@Component
public class LectureSearchRateLimit {

    private final KeyedRateLimiter member;
    private final KeyedRateLimiter guest;
    private final Counter memberRejected;
    private final Counter guestRejected;
    private final Counter skipped;

    public LectureSearchRateLimit(@Qualifier(LectureSearchConfig.MEMBER_RATE_LIMITER) KeyedRateLimiter member,
            @Qualifier(LectureSearchConfig.GUEST_RATE_LIMITER) KeyedRateLimiter guest, MeterRegistry registry) {
        this.member = member;
        this.guest = guest;
        String target = LectureIndexDefinition.METRIC_TARGET;
        this.memberRejected = Counter.builder("hapbang.search.rate.limit.rejected").tag("target", target)
                .tag("requester", "member").description("검색 호출 제한으로 429를 준 수").register(registry);
        this.guestRejected = Counter.builder("hapbang.search.rate.limit.rejected").tag("target", target)
                .tag("requester", "guest").description("검색 호출 제한으로 429를 준 수").register(registry);
        this.skipped = Counter.builder("hapbang.search.rate.limit.skipped").tag("target", target)
                .description("client IP가 공인 주소가 아니라 IP 호출 제한을 건너뛴 비회원 검색 수").register(registry);
    }

    /**
     * @throws RateLimitExceededException 한도를 넘었을 때
     */
    public void acquire(Requester requester) {
        if (requester.userId() != null) {
            acquire(member, String.valueOf(requester.userId()), memberRejected);
        } else if (requester.clientIpKey() != null) {
            acquire(guest, requester.clientIpKey(), guestRejected);
        } else {
            skipped.increment();
        }
    }

    private static void acquire(KeyedRateLimiter limiter, String key, Counter rejected) {
        try {
            limiter.acquire(key);
        } catch (RateLimitExceededException e) {
            rejected.increment();
            throw e;
        }
    }
}
