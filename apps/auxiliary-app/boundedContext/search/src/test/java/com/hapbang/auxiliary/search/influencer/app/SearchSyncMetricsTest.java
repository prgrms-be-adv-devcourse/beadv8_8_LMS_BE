package com.hapbang.auxiliary.search.influencer.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.ConsistencyCheck;
import com.hapbang.auxiliary.search.common.out.ConsistencyCheckStore;
import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.support.InMemoryConsistencyCheckStore;
import com.hapbang.auxiliary.search.support.InMemorySyncCursorStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 검색 정합성 지표(#772). 커서는 메모리 가짜다 */
class SearchSyncMetricsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 20);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemorySyncCursorStore cursors = new InMemorySyncCursorStore();
    private final InMemoryConsistencyCheckStore checks = new InMemoryConsistencyCheckStore();
    private final SearchSyncMetrics metrics = new SearchSyncMetrics(registry, cursors, checks,
            Clock.fixed(NOW.atZone(MemberChangeReconciler.ZONE).toInstant(), MemberChangeReconciler.ZONE));

    @Test
    @DisplayName("보정 지연은 지금 - 마지막으로 끝까지 읽은 시각(초)이다. 커서 시각이 오래돼도 상관없다")
    void reconcileLag() {
        cursors.moveTo(SyncCursor.MEMBER_CHANGES, NOW.minusHours(6), 7L);
        cursors.caughtUp(SyncCursor.MEMBER_CHANGES, NOW.minusMinutes(10));

        assertThat(lag()).isEqualTo(600.0);
    }

    @Test
    @DisplayName("끝까지 읽은 적이 없거나 커서를 읽지 못하면 지연 값이 없다(NaN)")
    void reconcileLagEmpty() {
        assertThat(lag()).isNaN();

        cursors.moveTo(SyncCursor.MEMBER_CHANGES, NOW, 0L);
        assertThat(lag()).isNaN();

        SimpleMeterRegistry brokenRegistry = new SimpleMeterRegistry();
        new SearchSyncMetrics(brokenRegistry, new InMemorySyncCursorStore() {
            @Override
            public Optional<SyncCursor> find(String name) {
                throw new IllegalStateException("DB 장애");
            }
        }, checks);
        assertThat(brokenRegistry.get("hapbang.search.reconcile.lag").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("문서 수 차이는 DB의 점검 결과(원본 − index)다. 점검 결과가 없으면 비어 있다(NaN)")
    void consistencyDifference() {
        assertThat(difference()).isNaN();

        checks.save(new ConsistencyCheck(ConsistencyCheck.INFLUENCER, 120, 122, NOW.minusMinutes(10)));

        assertThat(difference()).isEqualTo(-2.0);
    }

    @Test
    @DisplayName("점검한 지 25분이 지난 결과는 낡은 값으로 보고 비운다. DB를 읽지 못해도 비운다")
    void consistencyStaleOrUnreadable() {
        checks.save(new ConsistencyCheck(ConsistencyCheck.INFLUENCER, 120, 118, NOW.minusMinutes(25)));
        assertThat(difference()).isNaN();

        checks.save(new ConsistencyCheck(ConsistencyCheck.INFLUENCER, 120, 118, NOW.minusMinutes(24)));
        assertThat(difference()).isEqualTo(2.0);

        SimpleMeterRegistry brokenRegistry = new SimpleMeterRegistry();
        new SearchSyncMetrics(brokenRegistry, cursors, new ConsistencyCheckStore(null) {
            @Override
            public Optional<ConsistencyCheck> find(String target) {
                throw new IllegalStateException("DB 장애");
            }
        });
        assertThat(brokenRegistry.get("hapbang.search.consistency.difference").tag("target", "influencer").gauge()
                .value()).isNaN();
    }

    @Test
    @DisplayName("보정 결과는 kind별로 세고, 소요 시간은 성공·실패로 나눈다")
    void reconcileCounts() {
        metrics.reconciled(new MemberChangeReconciler.ReconcileResult(1, 3,
                Map.of(ReconcileKind.HIDDEN, 1L, ReconcileKind.ALREADY_APPLIED, 2L)), Duration.ofMillis(30));
        metrics.reconcileFailed(Duration.ofMillis(5));

        assertThat(registry.get("hapbang.search.reconcile.members").tag("kind", "hidden").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("hapbang.search.reconcile.members").tag("kind", "already_applied").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("hapbang.search.reconcile.duration").tag("outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("hapbang.search.reconcile.duration").tag("outcome", "failure").timer().count())
                .isEqualTo(1);
    }

    private double lag() {
        return registry.get("hapbang.search.reconcile.lag").gauge().value();
    }

    private double difference() {
        return registry.get("hapbang.search.consistency.difference").tag("target", "influencer").gauge().value();
    }
}
