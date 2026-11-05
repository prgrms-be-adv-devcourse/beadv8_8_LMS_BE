package com.hapbang.lecture.app;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.config.LectureFileProperties;
import com.hapbang.lecture.domain.FileUploadBusyException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;

/**
 * 파일 본문 업로드 동시 수 상한 (D81). 앱 전체에서 {@code lecture.file.upload-max-concurrent}(기본 20)개까지만 동시에 받는다.
 * <ul>
 *     <li>업로드 하나가 Tomcat 요청 스레드 하나를 최대 30분(Envoy) 잡는다. 상한이 없으면 느린 업로드가 스레드를 모두 잡아 다른 API가 멈춘다.</li>
 *     <li>기다리지 않는다. 자리가 없으면 본문을 읽기 전에 {@link FileUploadBusyException}(503, {@code Retry-After: 60})으로 거절한다.
 *     파일은 업로드 대기({@code PENDING})로 남아 다시 올릴 수 있다. 회원별 상한은 두지 않는다.</li>
 *     <li>자리는 업로드 전 검증부터 상태 변경까지 잡고, 실패해도 돌려준다.</li>
 *     <li>지표는 Resilience4j 기본값({@code resilience4j.bulkhead.*}, {@code name=lecture-file-upload})이다. 거절은 WARN 로그로 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class FileUploadBulkhead implements MeterBinder {

    static final String NAME = "lecture-file-upload";
    static final long RETRY_AFTER_SECONDS = 60;

    private final BulkheadRegistry registry;
    private final Bulkhead bulkhead;

    public FileUploadBulkhead(LectureFileProperties properties) {
        this.registry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.uploadMaxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());
        this.bulkhead = registry.bulkhead(NAME);
    }

    /**
     * 자리를 얻어 업로드를 실행하고, 끝나면(실패 포함) 자리를 돌려준다.
     *
     * @throws FileUploadBusyException 자리가 없을 때. 업로드는 실행하지 않는다
     */
    public void run(Long fileId, Runnable upload) {
        if (!bulkhead.tryAcquirePermission()) {
            log.warn("동시 업로드가 상한에 닿아 업로드를 거절한다 fileId={}, maxConcurrent={}", fileId,
                    bulkhead.getBulkheadConfig().getMaxConcurrentCalls());
            throw new FileUploadBusyException(RETRY_AFTER_SECONDS);
        }
        try {
            upload.run();
        } finally {
            bulkhead.onComplete();
        }
    }

    /** 지금 남은 자리 수 (테스트·진단용) */
    int availableSlots() {
        return bulkhead.getMetrics().getAvailableConcurrentCalls();
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
    }
}
