package com.hapbang.lecture.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.S3MockTestcontainersConfig;

// 힙보다 큰 본문을 올려 업로드가 본문을 메모리에 모으지 않는지 확인한다. 본문을 메모리에 모으면 OOM으로 실패한다.
// 시간이 걸려 check에서 빼고 수동으로 실행한다: ./gradlew :lecture-app:largeUploadTest (힙 256MB, docs/deploy/s3.md)
@Tag("large-upload")
@SpringBootTest(classes = S3StorageConfig.class,
        properties = {"spring.config.name=hapbang-module-test",
                "hapbang.storage.s3.bucket=" + S3MockTestcontainersConfig.BUCKET})
@Import(S3MockTestcontainersConfig.class)
class S3FileStorageLargeUploadTest {

    private static final long MB = 1024 * 1024;

    @Autowired
    S3FileStorage storage;

    @Test
    @DisplayName("힙보다 큰 파일도 메모리에 모으지 않고 업로드한다")
    void uploadLargerThanHeap() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long size = maxHeap * 2;
        String key = "lecture/LECTURE_VIDEO/" + UUID.randomUUID();
        List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
        System.gc();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);

        long start = System.nanoTime();
        storage.upload(key, "video/mp4", size, new GeneratedInputStream(size));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        long peakHeap = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        System.out.printf("[large-upload] 본문 %dMB, 최대 힙 %dMB, 업로드 중 힙 최대 사용 %dMB, %dms%n",
                size / MB, maxHeap / MB, peakHeap / MB, elapsedMillis);
        assertThat(storage.find(key)).hasValueSatisfying(file -> assertThat(file.size()).isEqualTo(size));
    }

    /** 정해진 크기만큼 바이트를 만들어 내는 스트림. 본문을 미리 만들어 두지 않는다 */
    private static final class GeneratedInputStream extends InputStream {

        private final long size;
        private long position;

        GeneratedInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            return position < size ? (int) (position++ & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= size) {
                return -1;
            }
            int count = (int) Math.min(len, size - position);
            for (int i = 0; i < count; i++) {
                b[off + i] = (byte) (position++ & 0xFF);
            }
            return count;
        }
    }
}
