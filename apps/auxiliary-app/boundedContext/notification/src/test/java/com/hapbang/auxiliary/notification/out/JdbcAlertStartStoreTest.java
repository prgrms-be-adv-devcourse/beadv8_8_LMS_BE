package com.hapbang.auxiliary.notification.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.notification.app.AlertStartStore;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 알림 시작 시각 기록(ND-095). 처음 한 번만 쓰고 그 뒤에는 처음 값을 돌려준다(재배포·재시작 흉내). 여러 pod가 처음 동시에 떠도
 * 값은 하나다(동시 기록 흉내). 시험마다 다른 키를 써서 서로 섞이지 않는다.
 */
@IntegrationTest
class JdbcAlertStartStoreTest {

    @Autowired
    private AlertStartStore store;

    @Test
    void 처음에는_기록하고_다시_불러도_처음_값을_돌려준다() {
        String key = "test-" + UUID.randomUUID();
        Instant first = Instant.parse("2026-11-11T05:00:00.123456Z");

        assertThat(store.recordIfAbsent(key, first)).isEqualTo(first);
        assertThat(store.recordIfAbsent(key, first.plusSeconds(3600))).isEqualTo(first);
    }

    @Test
    void 동시에_처음_기록해도_값은_하나다() throws Exception {
        String key = "test-" + UUID.randomUUID();
        int pods = 8;
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(pods);
        try {
            List<Future<Instant>> results = new ArrayList<>();
            for (int i = 0; i < pods; i++) {
                Instant now = Instant.parse("2026-11-11T05:00:00Z").plusSeconds(i);
                Callable<Instant> record = () -> {
                    ready.await();
                    return store.recordIfAbsent(key, now);
                };
                results.add(executor.submit(record));
            }
            ready.countDown();

            Set<Instant> values = new java.util.HashSet<>();
            for (Future<Instant> result : results) {
                values.add(result.get());
            }
            assertThat(values).hasSize(1);
            assertThat(values.stream().collect(Collectors.toList()).getFirst())
                    .isBetween(Instant.parse("2026-11-11T05:00:00Z"), Instant.parse("2026-11-11T05:00:07Z"));
        } finally {
            executor.shutdownNow();
        }
    }
}
