package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.config.LectureConfig;
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
 *     <li>{@code lecture.hp_reconciliation.last.completed}: 끝까지 비교한 마지막 실행이 끝난 시각(epoch 초, 없으면 0, #805)</li>
 *     <li>{@code lecture.hp_reconciliation.runs{status}}: 끝난 실행 횟수 (완료·중단)</li>
 * </ul>
 * 앞의 두 값은 메모리에 있어 앱이 다시 뜨면 0부터 시작하므로, 기동할 때 {@link LectureHpReconciliationMetricsRestorer}가
 * DB의 마지막 완료 실행으로 채운다(#805). 그러지 않으면 다음 대사 전까지 0이라 Grafana alert가 거짓 복구 통보를 보낸다.
 * 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다. 클러스터 안의 Prometheus가 관리 포트의 {@code /actuator/prometheus}로 수집하고,
 * Grafana alert rule은 {@code k8s/platform/base/alerting/rules.yaml}에 있다.
 */
@Component
public class LectureHpReconciliationMetrics {

    static final String MISMATCHES = "lecture.hp_reconciliation.mismatches";
    static final String LAST_COMPLETED = "lecture.hp_reconciliation.last.completed";
    static final String RUNS = "lecture.hp_reconciliation.runs";

    private final MeterRegistry meterRegistry;
    private final Map<LectureHpReconciliationRule, AtomicLong> mismatchCounts =
            new EnumMap<>(LectureHpReconciliationRule.class);
    private final AtomicLong lastCompletedEpochSeconds = new AtomicLong();

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
        Gauge.builder(LAST_COMPLETED, lastCompletedEpochSeconds, AtomicLong::get)
                .description("끝까지 비교한 마지막 강의 HP 대사가 끝난 시각(epoch 초). 없으면 0")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /** 끝까지 비교한 실행의 규칙별 건수로 바꾼다 (이번에 없는 규칙은 0) */
    public void updateMismatches(Map<LectureHpReconciliationRule, Long> counts) {
        mismatchCounts.forEach((rule, count) -> count.set(counts.getOrDefault(rule, 0L)));
    }

    /** 끝까지 비교한 실행이 끝난 시각(강의 시간대 {@link LectureConfig#ZONE})으로 바꾼다 */
    public void updateLastCompleted(LocalDateTime finishedAt) {
        lastCompletedEpochSeconds.set(finishedAt.atZone(LectureConfig.ZONE).toEpochSecond());
    }

    public void recordRun(LectureHpReconciliationStatus status) {
        meterRegistry.counter(RUNS, "status", status.name()).increment();
    }

    /** 마지막으로 바꾼 규칙별 건수 (테스트·확인용) */
    public long mismatches(LectureHpReconciliationRule rule) {
        return mismatchCounts.get(rule).get();
    }

    /** 끝까지 비교한 마지막 실행이 끝난 시각(epoch 초, 테스트·확인용) */
    public long lastCompletedEpochSeconds() {
        return lastCompletedEpochSeconds.get();
    }

    /** 상태별 끝난 실행 횟수 (테스트·확인용) */
    public double runs(LectureHpReconciliationStatus status) {
        return meterRegistry.counter(RUNS, "status", status.name()).count();
    }
}
