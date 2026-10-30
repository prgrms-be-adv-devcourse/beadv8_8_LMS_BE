package com.hapbang.appearance.app;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * HP 작업 처리 지표 (Micrometer).
 * <ul>
 *     <li>{@code appearance.hp_task.executions{result=done|retry|dead}}: 실행 결과 횟수</li>
 *     <li>{@code appearance.hp_task.pending}, {@code appearance.hp_task.dead}: 대기·격리 작업 수. 재시도 배치가 돌 때마다 갱신한다
 *     (지표를 읽을 때마다 DB를 조회하지 않도록)</li>
 * </ul>
 * 앱에서는 Actuator가 만든 {@link MeterRegistry}에 등록된다. 출연 모듈 단독 테스트처럼 레지스트리가 없으면 메모리 레지스트리를 쓴다.
 * 지표를 밖에서 보는 경로(/actuator/metrics, Prometheus)는 팀 합의 후 연다.
 */
@Component
public class HpTaskMetrics {

    static final String EXECUTIONS = "appearance.hp_task.executions";
    static final String RESULT = "result";

    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong deadCount = new AtomicLong();

    public HpTaskMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        Gauge.builder("appearance.hp_task.pending", pendingCount, AtomicLong::get)
                .description("대기 중인 HP 작업 수 (재시도 배치 실행 시점)")
                .register(meterRegistry);
        Gauge.builder("appearance.hp_task.dead", deadCount, AtomicLong::get)
                .description("격리된 HP 작업 수 (재시도 배치 실행 시점). 0보다 크면 확인이 필요하다")
                .register(meterRegistry);
    }

    public void recordDone() {
        increment("done");
    }

    public void recordRetry() {
        increment("retry");
    }

    public void recordDead() {
        increment("dead");
    }

    public void updateBacklog(long pending, long dead) {
        pendingCount.set(pending);
        deadCount.set(dead);
    }

    /** 결과별 실행 횟수 (테스트·확인용) */
    public double executions(String result) {
        return meterRegistry.counter(EXECUTIONS, RESULT, result).count();
    }

    /** 마지막으로 갱신한 대기 작업 수 (테스트·확인용) */
    public long pendingCount() {
        return pendingCount.get();
    }

    /** 마지막으로 갱신한 격리 작업 수 (테스트·확인용) */
    public long deadCount() {
        return deadCount.get();
    }

    private void increment(String result) {
        meterRegistry.counter(EXECUTIONS, RESULT, result).increment();
    }
}
