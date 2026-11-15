package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.SyncRun;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 싱크 회차 지표 {@code hapbang.chatbot.sync.*} (#716). 회차가 끝날 때(건너뜀 포함) 한 번 낸다.
 * <ul>
 *     <li>{@code runs{type, status}}: 회차 수. 실패·건너뜀이 이어지면 알림 대상</li>
 *     <li>{@code fixed{type}}: 고친 건수(원본 새것 + 같은 version 다른 값). 넣은 건수는 {@code inserted{type}}</li>
 *     <li>{@code warnings{type}}: 경고 회차 수(어긋남 비율이 큼)</li>
 *     <li>{@code duration{type, status}}: 걸린 시간</li>
 * </ul>
 */
@Component
class SyncMetrics {

    static final String PREFIX = "hapbang.chatbot.sync.";

    private final ObjectProvider<MeterRegistry> registry;

    SyncMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    void record(SyncRun run, Duration took) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        String type = run.getType().name();
        String status = run.getStatus().name();
        Counter.builder(PREFIX + "runs").description("챗봇 싱크 회차 수")
                .tag("type", type).tag("status", status).register(meters).increment();
        Counter.builder(PREFIX + "fixed").description("챗봇 싱크가 고친 사본 행 수")
                .tag("type", type).register(meters).increment(run.getFixed());
        Counter.builder(PREFIX + "inserted").description("챗봇 싱크가 넣은 사본 행 수")
                .tag("type", type).register(meters).increment(run.getInserted());
        Counter warnings = Counter.builder(PREFIX + "warnings").description("어긋남 비율이 커 경고한 챗봇 싱크 회차 수")
                .tag("type", type).register(meters);
        if (run.isWarning()) {
            warnings.increment();
        }
        Timer.builder(PREFIX + "duration").description("챗봇 싱크 회차가 걸린 시간")
                .tag("type", type).tag("status", status).register(meters).record(took);
    }
}
