package com.hapbang.auxiliary.search.lecture.app;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 강의 검색 보정 지표(#884). 인플루언서 보정 지표({@code hapbang.search.reconcile.*})와 이름을 나눈다. 같은 이름에 태그 키가 다르면 Prometheus가
 * 받지 않기 때문이다.
 * <ul>
 *     <li>{@code hapbang.search.lecture.reconcile.items{job, kind}}: 보정이 본 항목 수({@link LectureReconcileKind}). 놓친 이벤트가 있으면
 *     보정인 kind(inserted·overwritten·hidden·copied·review_deleted·seller_fixed·rating_fixed)가 는다</li>
 *     <li>{@code hapbang.search.lecture.reconcile.duration{job, outcome}}: 보정 1회 소요 시간. outcome = success·failure</li>
 * </ul>
 * job = lecture(강의 10분)·review(리뷰 1시간, 판매자·평점 칸 맞추기 포함)·seller(판매자 사본 10분)
 */
@Component
public class LectureSyncMetrics {

    static final String JOB_LECTURE = "lecture";
    static final String JOB_REVIEW = "review";
    static final String JOB_SELLER = "seller";

    private final MeterRegistry registry;

    public LectureSyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void reconciled(String job, LectureReconcileResult result, Duration took) {
        result.counts().forEach((kind, count) -> registry.counter("hapbang.search.lecture.reconcile.items",
                "job", job, "kind", kind.tag()).increment(count));
        timer(job, "success").record(took);
    }

    void reconcileFailed(String job, Duration took) {
        timer(job, "failure").record(took);
    }

    private Timer timer(String job, String outcome) {
        return Timer.builder("hapbang.search.lecture.reconcile.duration").tag("job", job).tag("outcome", outcome)
                .description("강의 검색 보정 1회 소요 시간").register(registry);
    }
}
