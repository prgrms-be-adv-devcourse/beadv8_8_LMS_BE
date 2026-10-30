package com.hapbang.lecture.app;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 강의 HP 작업 지표 (Micrometer, D57 (8)). 출연 {@code HpTaskMetrics}와 같은 방식이다.
 * <ul>
 *     <li>{@code lecture.hp_task.executions{result=succeeded|rejected|retry|dead}}: 결과를 기록한 실행 횟수</li>
 *     <li>{@code lecture.hp_task.dead}: {@code DEAD}가 된 횟수. 0보다 크게 늘면 확인이 필요하다</li>
 * </ul>
 * 레지스트리가 없는 테스트에서는 메모리 레지스트리를 쓴다. 클러스터 안의 Prometheus가 {@code /actuator/prometheus}로 수집한다(#480).
 */
@Component
public class LectureHpTaskMetrics {

    static final String EXECUTIONS = "lecture.hp_task.executions";
    static final String DEAD = "lecture.hp_task.dead";

    private final MeterRegistry meterRegistry;
    private final Counter deadCounter;

    public LectureHpTaskMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.deadCounter = Counter.builder(DEAD)
                .description("DEAD가 된 강의 HP 작업 수")
                .register(meterRegistry);
    }

    public void recordSucceeded() {
        increment("succeeded");
    }

    public void recordRejected() {
        increment("rejected");
    }

    public void recordRetry() {
        increment("retry");
    }

    public void recordDead() {
        increment("dead");
        deadCounter.increment();
    }

    private void increment(String result) {
        meterRegistry.counter(EXECUTIONS, "result", result).increment();
    }
}
