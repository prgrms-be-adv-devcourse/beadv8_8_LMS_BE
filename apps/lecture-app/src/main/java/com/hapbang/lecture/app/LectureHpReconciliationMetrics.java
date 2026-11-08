package com.hapbang.lecture.app;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 서비스 간 HP 대사 지표 (#580, D74, Micrometer). 출연 {@code HpReconciliationMetrics}와 같은 모양이다.
 * <ul>
 *     <li>{@code lecture.hp_reconciliation.mismatches{rule}}: 마지막으로 끝까지 비교한 실행의 규칙별 어긋난 건수
 *     ({@code HP_TASK_DEAD}는 DEAD 작업 때문에 표시만 한 건수). 0보다 크면 확인이 필요하다.
 *     중단된 실행은 일부만 비교해 값을 바꾸지 않는다</li>
 *     <li>{@code lecture.hp_reconciliation.runs{status}}: 끝난 실행 횟수 (완료·중단)</li>
 * </ul>
 * 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다. 클러스터 안의 Prometheus가 관리 포트의 {@code /actuator/prometheus}로 수집한다.
 * 알림 규칙은 아직 없다(#343).
 */
@Component
public class LectureHpReconciliationMetrics {

    static final String MISMATCHES = "lecture.hp_reconciliation.mismatches";
    static final String RUNS = "lecture.hp_reconciliation.runs";

    private final MeterRegistry meterRegistry;
    private final Map<LectureHpReconciliationRule, AtomicLong> mismatchCounts =
            new EnumMap<>(LectureHpReconciliationRule.class);

    public LectureHpReconciliationMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        for (LectureHpReconciliationRule rule : LectureHpReconciliationRule.values()) {
            AtomicLong count = new AtomicLong();
            mismatchCounts.put(rule, count);
            Gauge.builder(MISMATCHES, count, AtomicLong::get)
                    .description("마지막으로 끝까지 비교한 강의 HP 대사의 규칙별 어긋난 건수")
                    .tag("rule", rule.name())
                    .register(meterRegistry);
        }
    }

    /** 끝까지 비교한 실행의 규칙별 건수로 바꾼다 (이번에 없는 규칙은 0) */
    public void updateMismatches(Map<LectureHpReconciliationRule, Long> counts) {
        mismatchCounts.forEach((rule, count) -> count.set(counts.getOrDefault(rule, 0L)));
    }

    public void recordRun(LectureHpReconciliationStatus status) {
        meterRegistry.counter(RUNS, "status", status.name()).increment();
    }

    /** 마지막으로 바꾼 규칙별 건수 (테스트·확인용) */
    public long mismatches(LectureHpReconciliationRule rule) {
        return mismatchCounts.get(rule).get();
    }

    /** 상태별 끝난 실행 횟수 (테스트·확인용) */
    public double runs(LectureHpReconciliationStatus status) {
        return meterRegistry.counter(RUNS, "status", status.name()).count();
    }
}
