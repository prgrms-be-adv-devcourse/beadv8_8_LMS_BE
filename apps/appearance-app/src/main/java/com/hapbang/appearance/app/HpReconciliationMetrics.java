package com.hapbang.appearance.app;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 서비스 간 HP 대사 지표 (#581, Micrometer).
 * <ul>
 *     <li>{@code appearance.hp_reconciliation.mismatches{rule}}: 마지막으로 끝까지 비교한 실행의 규칙별 어긋난 건수
 *     ({@code HP_TASK_DEAD}는 격리 작업 때문에 표시만 한 건수). 0보다 크면 확인이 필요하다.
 *     중단된 실행은 일부만 비교해 값을 바꾸지 않는다</li>
 *     <li>{@code appearance.hp_reconciliation.runs{status}}: 끝난 실행 횟수 (완료·중단)</li>
 * </ul>
 * 클러스터 안의 Prometheus가 관리 포트의 {@code /actuator/prometheus}로 수집한다(#528).
 */
@Component
public class HpReconciliationMetrics {

    static final String MISMATCHES = "appearance.hp_reconciliation.mismatches";
    static final String RUNS = "appearance.hp_reconciliation.runs";

    private final MeterRegistry meterRegistry;
    private final Map<HpReconciliationRule, AtomicLong> mismatchCounts = new EnumMap<>(HpReconciliationRule.class);

    public HpReconciliationMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        for (HpReconciliationRule rule : HpReconciliationRule.values()) {
            AtomicLong count = new AtomicLong();
            mismatchCounts.put(rule, count);
            Gauge.builder(MISMATCHES, count, AtomicLong::get)
                    .description("마지막으로 끝까지 비교한 HP 대사의 규칙별 어긋난 건수")
                    .tag("rule", rule.name())
                    .register(meterRegistry);
        }
    }

    /** 끝까지 비교한 실행의 규칙별 건수로 바꾼다 (이번에 없는 규칙은 0) */
    public void updateMismatches(Map<HpReconciliationRule, Long> counts) {
        mismatchCounts.forEach((rule, count) -> count.set(counts.getOrDefault(rule, 0L)));
    }

    public void recordRun(HpReconciliationStatus status) {
        meterRegistry.counter(RUNS, "status", status.name()).increment();
    }

    /** 마지막으로 바꾼 규칙별 건수 (테스트·확인용) */
    public long mismatches(HpReconciliationRule rule) {
        return mismatchCounts.get(rule).get();
    }

    /** 상태별 끝난 실행 횟수 (테스트·확인용) */
    public double runs(HpReconciliationStatus status) {
        return meterRegistry.counter(RUNS, "status", status.name()).count();
    }
}
