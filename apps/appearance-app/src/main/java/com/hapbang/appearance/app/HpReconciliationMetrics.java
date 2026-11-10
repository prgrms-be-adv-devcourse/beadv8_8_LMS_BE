package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.shared.time.AppearanceTime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 서비스 간 HP 대사 지표 (#581, Micrometer).
 * <ul>
 *     <li>{@code appearance.hp_reconciliation.mismatches{rule}}: 마지막으로 끝까지 비교한 실행의 규칙별 어긋난 건수
 *     ({@code HP_TASK_DEAD}는 격리 작업 때문에 표시만 한 건수). 0보다 크면 확인이 필요하다.
 *     중단된 실행은 일부만 비교해 값을 바꾸지 않는다</li>
 *     <li>{@code appearance.hp_reconciliation.last.completed}: 끝까지 비교한 마지막 실행이 끝난 시각(epoch 초, 없으면 0, #821)</li>
 *     <li>{@code appearance.hp_reconciliation.runs{status}}: 끝난 실행 횟수 (완료·중단)</li>
 * </ul>
 * 앞의 두 값은 메모리에 있어 앱이 다시 뜨면 0부터 시작하므로, 기동할 때 {@link HpReconciliationMetricsRestorer}가
 * DB의 마지막 완료 실행으로 채운다(#821). 그러지 않으면 다음 대사 전까지 0이라 Grafana alert가 거짓 복구 통보를 보낸다.
 * 클러스터 안의 Prometheus가 관리 포트의 {@code /actuator/prometheus}로 수집한다(#528).
 */
@Component
public class HpReconciliationMetrics {

    static final String MISMATCHES = "appearance.hp_reconciliation.mismatches";
    static final String LAST_COMPLETED = "appearance.hp_reconciliation.last.completed";
    static final String RUNS = "appearance.hp_reconciliation.runs";

    private final MeterRegistry meterRegistry;
    private final Map<HpReconciliationRule, AtomicLong> mismatchCounts = new EnumMap<>(HpReconciliationRule.class);
    private final AtomicLong lastCompletedEpochSeconds = new AtomicLong();

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
        Gauge.builder(LAST_COMPLETED, lastCompletedEpochSeconds, AtomicLong::get)
                .description("끝까지 비교한 마지막 HP 대사가 끝난 시각(epoch 초). 없으면 0")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /** 끝까지 비교한 실행의 규칙별 건수로 바꾼다 (이번에 없는 규칙은 0) */
    public void updateMismatches(Map<HpReconciliationRule, Long> counts) {
        mismatchCounts.forEach((rule, count) -> count.set(counts.getOrDefault(rule, 0L)));
    }

    /** 끝까지 비교한 실행이 끝난 시각(출연 시간대 {@link AppearanceTime#ZONE})으로 바꾼다 */
    public void updateLastCompleted(LocalDateTime finishedAt) {
        lastCompletedEpochSeconds.set(finishedAt.atZone(AppearanceTime.ZONE).toEpochSecond());
    }

    public void recordRun(HpReconciliationStatus status) {
        meterRegistry.counter(RUNS, "status", status.name()).increment();
    }

    /** 마지막으로 바꾼 규칙별 건수 (테스트·확인용) */
    public long mismatches(HpReconciliationRule rule) {
        return mismatchCounts.get(rule).get();
    }

    /** 끝까지 비교한 마지막 실행이 끝난 시각(epoch 초, 테스트·확인용) */
    public long lastCompletedEpochSeconds() {
        return lastCompletedEpochSeconds.get();
    }

    /** 상태별 끝난 실행 횟수 (테스트·확인용) */
    public double runs(HpReconciliationStatus status) {
        return meterRegistry.counter(RUNS, "status", status.name()).count();
    }
}
