package com.hapbang.auxiliary.search.app;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 검색 지표(#771). rate limit을 정하기 전 근거(요청 수·ES 응답 시간 p95)와 사전·동의어 보강 신호(결과 0건)를 남긴다.
 * <ul>
 *     <li>{@code hapbang.search.requests{target, outcome}}: 요청 수. outcome = ok(결과 있음)·empty(0건)·error(ES 장애)</li>
 *     <li>{@code hapbang.search.es.latency{target}}: ES 검색 시간. p95를 볼 수 있게 histogram을 낸다</li>
 *     <li>{@code hapbang.search.zero.results{target}}: 검색어가 있는데 결과가 0건인 요청 수(검색어는 {@link ZeroResultKeywords}가 따로 모은다)</li>
 * </ul>
 * 태그에 검색어를 넣지 않는다(값 종류가 끝없이 늘고 닉네임일 수 있다).
 */
@Component
public class SearchMetrics {

    static final String TARGET_INFLUENCER = "influencer";

    private final MeterRegistry registry;
    private final Timer influencerLatency;
    private final Counter influencerZeroResults;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.influencerLatency = Timer.builder("hapbang.search.es.latency").tag("target", TARGET_INFLUENCER)
                .description("검색 ES 요청 시간").publishPercentileHistogram().register(registry);
        this.influencerZeroResults = Counter.builder("hapbang.search.zero.results").tag("target", TARGET_INFLUENCER)
                .description("검색어가 있는데 결과가 0건인 검색 수").register(registry);
    }

    void influencerSearched(String outcome, Duration latency) {
        registry.counter("hapbang.search.requests", "target", TARGET_INFLUENCER, "outcome", outcome).increment();
        influencerLatency.record(latency);
    }

    void influencerZeroResult() {
        influencerZeroResults.increment();
    }
}
