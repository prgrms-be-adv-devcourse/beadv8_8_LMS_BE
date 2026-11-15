package com.hapbang.auxiliary.search.common.app;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 검색 API 지표(#771, 공통 코드 #868). rate limit을 정하기 전 근거(요청 수·ES 응답 시간 p95)와 사전·동의어 보강 신호(결과 0건)를 남긴다.
 * 대상마다 {@link #forTarget}으로 한 묶음을 받아 쓴다.
 * <ul>
 *     <li>{@code hapbang.search.requests{target, outcome}}: 요청 수. outcome = ok(결과 있음)·empty(0건)·error(ES 장애)</li>
 *     <li>{@code hapbang.search.es.latency{target}}: ES 검색 시간. p95를 볼 수 있게 histogram을 낸다</li>
 *     <li>{@code hapbang.search.zero.results{target}}: 검색어가 있는데 결과가 0건인 요청 수(검색어는 {@link ZeroResultKeywords}가 따로 모은다)</li>
 * </ul>
 * 태그에 검색어를 넣지 않는다(값 종류가 끝없이 늘고 닉네임일 수 있다).
 */
@Component
public class SearchMetrics {

    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 대상 하나의 검색 지표. 대상의 Facade가 만들어질 때 한 번 부른다. ES 응답 시간과 0건 지표를 그때 등록해 첫 요청 전부터 0으로 보이게 한다.
     *
     * @param target 지표 태그 값(예: {@code influencer})
     */
    public Target forTarget(String target) {
        return new Target(target);
    }

    /** 대상 하나의 검색 지표 */
    public final class Target {

        private final String target;
        private final Timer latency;
        private final Counter zeroResults;

        private Target(String target) {
            this.target = target;
            this.latency = Timer.builder("hapbang.search.es.latency").tag("target", target)
                    .description("검색 ES 요청 시간").publishPercentileHistogram().register(registry);
            this.zeroResults = Counter.builder("hapbang.search.zero.results").tag("target", target)
                    .description("검색어가 있는데 결과가 0건인 검색 수").register(registry);
        }

        /**
         * @param outcome ok(결과 있음)·empty(0건)·error(ES 장애)
         */
        public void searched(String outcome, Duration took) {
            registry.counter("hapbang.search.requests", "target", target, "outcome", outcome).increment();
            latency.record(took);
        }

        /** 필터 없이 검색어만으로 0건이었다 */
        public void zeroResult() {
            zeroResults.increment();
        }
    }
}
