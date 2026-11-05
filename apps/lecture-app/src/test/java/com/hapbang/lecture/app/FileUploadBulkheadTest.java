package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.hapbang.lecture.config.LectureFileProperties;
import com.hapbang.lecture.domain.FileUploadBusyException;
import com.hapbang.lecture.domain.LectureErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 파일 본문 업로드 동시 수 상한 (D81): 자리가 없으면 기다리지 않고 업로드를 실행하지 않으며, 끝나면(실패 포함) 자리를 돌려준다
class FileUploadBulkheadTest {

    @Test
    void 기본_상한은_20이고_1보다_작으면_뜨지_않는다() {
        assertThat(new LectureFileProperties(null).uploadMaxConcurrent()).isEqualTo(20);
        assertThatThrownBy(() -> new LectureFileProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자리가_없으면_업로드를_실행하지_않고_503_FILE_UPLOAD_BUSY와_60초를_담아_거절한다() throws Exception {
        FileUploadBulkhead bulkhead = new FileUploadBulkhead(new LectureFileProperties(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> bulkhead.run(1L, () -> {
                started.countDown();
                await(release);
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean ran = new AtomicBoolean();

            assertThatThrownBy(() -> bulkhead.run(2L, () -> ran.set(true)))
                    .isInstanceOfSatisfying(FileUploadBusyException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(LectureErrorCode.FILE_UPLOAD_BUSY);
                        assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
                        assertThat(e.retryAfterSeconds()).isEqualTo(60);
                    });
            assertThat(ran).isFalse();

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(bulkhead.availableSlots()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 업로드가_실패해도_자리를_돌려준다() {
        FileUploadBulkhead bulkhead = new FileUploadBulkhead(new LectureFileProperties(1));

        assertThatThrownBy(() -> bulkhead.run(1L, () -> {
            throw new IllegalStateException("S3 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(bulkhead.availableSlots()).isEqualTo(1);
        assertThatCode(() -> bulkhead.run(2L, () -> {
        })).doesNotThrowAnyException();
    }

    @Test
    void 남은_자리와_상한을_Resilience4j_지표로_낸다() {
        FileUploadBulkhead bulkhead = new FileUploadBulkhead(new LectureFileProperties(20));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        bulkhead.bindTo(registry);

        assertThat(registry.get("resilience4j.bulkhead.max.allowed.concurrent.calls")
                .tag("name", FileUploadBulkhead.NAME).gauge().value()).isEqualTo(20);
        assertThat(registry.get("resilience4j.bulkhead.available.concurrent.calls")
                .tag("name", FileUploadBulkhead.NAME).gauge().value()).isEqualTo(20);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
