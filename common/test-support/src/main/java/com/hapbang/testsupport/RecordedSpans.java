package com.hapbang.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * trace 계측 테스트에서 앱이 만든 span을 메모리에 모은다 (#579, #631).
 * <p>
 * {@link Config}를 {@code @Import}하면 span이 끝날 때마다 바로 여기로 내보낸다(SimpleSpanProcessor). OTLP endpoint가 없으므로
 * 밖으로는 보내지 않는다. 이 클래스를 쓰는 앱은 {@code opentelemetry-sdk-testing}을 testImplementation으로 둔다(루트
 * {@code build.gradle.kts}).
 */
public final class RecordedSpans {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    public List<SpanData> all() {
        return exporter.getFinishedSpanItems();
    }

    public void reset() {
        exporter.reset();
    }

    public List<SpanData> serverSpans(String traceId) {
        return all().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER && span.getTraceId().equals(traceId))
                .toList();
    }

    public List<SpanData> named(String name) {
        return all().stream().filter(span -> span.getName().equals(name)).toList();
    }

    public List<SpanData> clientSpans() {
        return all().stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
    }

    /** 지정한 trace를 뺀 나머지 span. 장벽 요청({@code barrierTraceId})이 만든 span을 빼고 볼 때 쓴다 */
    public List<SpanData> except(String barrierTraceId) {
        return all().stream().filter(span -> !span.getTraceId().equals(barrierTraceId)).toList();
    }

    /** 조건이 5초 안에 맞을 때까지 기다린다. span은 응답을 보낸 뒤에 끝나므로 응답만 보고 바로 확인하지 않는다 */
    public static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("5초 안에 조건이 맞아야 한다").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        RecordedSpans recordedSpans() {
            return new RecordedSpans();
        }

        // exporter를 SpanExporter 빈으로 두면 Boot의 BatchSpanProcessor에도 들어가 늦게 한 번 더 내보낸다. 처리기 빈만 둔다
        @Bean
        SpanProcessor recordedSpanProcessor(RecordedSpans spans) {
            return SimpleSpanProcessor.create(spans.exporter);
        }
    }
}
