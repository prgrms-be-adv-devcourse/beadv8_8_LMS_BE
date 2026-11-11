package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.out.ConsistencyCheck;
import com.hapbang.auxiliary.search.out.ConsistencyCheckStore;
import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.out.SyncCursorStore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 문서가 원본과 맞는지 보는 지표(#772). 검색 API 지표는 {@link SearchMetrics}다. 태그에 회원 ID를 넣지 않는다.
 * <ul>
 *     <li>{@code hapbang.search.reconcile.members{kind}}: 증분 보정이 반영한 회원 수({@link ReconcileKind}). inserted·hidden·overwritten이
 *     이벤트로 놓친 변경이고, 모든 kind의 합이 읽은 회원 수다.</li>
 *     <li>{@code hapbang.search.reconcile.duration{outcome}}: 보정 1회 소요 시간. outcome = success·failure</li>
 *     <li>{@code hapbang.search.reconcile.lag}(초): 지금 − 마지막으로 끝까지 읽은 시각(#772 Q3). 보정이 멈추면 커진다. DB의 커서에서 읽으므로
 *     어느 pod가 보정을 돌렸든 같은 값이다. 끝까지 읽은 적이 없거나 읽지 못하면 값이 없다(NaN).</li>
 *     <li>{@code hapbang.search.consistency.difference{target}}: 원본 정상 인플루언서 수 − ES 보이는 문서 수(통계 동기화 때). 양수면 빠진 문서,
 *     음수면 보이면 안 되는 문서가 있다. DB의 점검 결과({@code search.consistency_check})에서 읽으므로 어느 pod에서 봐도 같다(PR #833 리뷰 Q2).
 *     점검한 적이 없거나, 마지막 회차에 점검하지 못했거나, 점검한 지 {@link #CONSISTENCY_STALE_AFTER}가 지났으면 값이 없다(NaN). alert는 두지 않는다
 *     (#772 Q2, 필요해지면 붙인다).</li>
 *     <li>{@code hapbang.search.reindex.duration{reason, outcome}}: 처음 적재·재색인 1회 소요 시간(#773). reason = first_load·settings_changed,
 *     outcome = success·failure(alias 그대로)·catch_up_failed(alias는 옮겼지만 따라잡기를 못 함)</li>
 *     <li>{@code hapbang.search.reindex.documents{reason}}: 처음 적재·재색인으로 새 index에 넣은 정상 인플루언서 수(#773)</li>
 *     <li>{@code hapbang.search.synonyms.rejected}: ES가 동의어 묶음을 내용 때문에 거절한 횟수(#773, 10분 확인마다). 0이 아니면 synonyms.txt를
 *     고쳐야 한다</li>
 * </ul>
 */
@Slf4j
@Component
public class SearchSyncMetrics {

    /** 정합성 점검 결과를 지표로 내는 기간. 점검 주기(10분) 두 번이 지나도 새 결과가 없으면 낡은 값으로 보고 비운다 */
    static final Duration CONSISTENCY_STALE_AFTER = Duration.ofMinutes(25);

    /** 처음 적재·재색인 결과(#773) */
    static final String REINDEX_SUCCESS = "success";
    static final String REINDEX_FAILURE = "failure";
    static final String REINDEX_CATCH_UP_FAILED = "catch_up_failed";

    private final MeterRegistry registry;
    private final SyncCursorStore cursorStore;
    private final ConsistencyCheckStore consistencyChecks;
    private final Clock clock;

    @Autowired
    public SearchSyncMetrics(MeterRegistry registry, SyncCursorStore cursorStore,
            ConsistencyCheckStore consistencyChecks) {
        this(registry, cursorStore, consistencyChecks, Clock.system(MemberChangeReconciler.ZONE));
    }

    SearchSyncMetrics(MeterRegistry registry, SyncCursorStore cursorStore, ConsistencyCheckStore consistencyChecks,
            Clock clock) {
        this.registry = registry;
        this.cursorStore = cursorStore;
        this.consistencyChecks = consistencyChecks;
        this.clock = clock;
        Gauge.builder("hapbang.search.reconcile.lag", this, SearchSyncMetrics::reconcileLagSeconds)
                .description("지금 - 증분 보정이 마지막으로 끝까지 읽은 시각").baseUnit("seconds").strongReference(true)
                .register(registry);
        Gauge.builder("hapbang.search.consistency.difference", this, SearchSyncMetrics::consistencyDifference)
                .tag("target", SearchMetrics.TARGET_INFLUENCER).description("원본 정상 인플루언서 수 - 검색에 보이는 문서 수")
                .strongReference(true).register(registry);
    }

    /** 보정 한 회차가 끝났다 */
    void reconciled(MemberChangeReconciler.ReconcileResult result, Duration took) {
        result.counts().forEach((kind, count) ->
                registry.counter("hapbang.search.reconcile.members", "kind", kind.tag()).increment(count));
        duration("success").record(took);
    }

    /** 보정 한 회차가 중간에 실패했다(그때까지 반영한 회원 수는 세지 않는다) */
    void reconcileFailed(Duration took) {
        duration("failure").record(took);
    }

    /**
     * 처음 적재·재색인 한 번이 끝났다(#773).
     *
     * @param documents 새 index에 넣은 정상 인플루언서 수. 실패했으면 0
     */
    void reindexed(InfluencerReindexer.ReindexReason reason, String outcome, Duration took, long documents) {
        Timer.builder("hapbang.search.reindex.duration").tag("reason", reason.tag()).tag("outcome", outcome)
                .description("검색 처음 적재·재색인 1회 소요 시간").register(registry).record(took);
        if (documents > 0) {
            registry.counter("hapbang.search.reindex.documents", "reason", reason.tag()).increment(documents);
        }
    }

    /** ES가 동의어 묶음을 거절했다(#773) */
    void synonymsRejected() {
        registry.counter("hapbang.search.synonyms.rejected").increment();
    }

    private Timer duration(String outcome) {
        return Timer.builder("hapbang.search.reconcile.duration").tag("outcome", outcome)
                .description("증분 보정 1회 소요 시간").register(registry);
    }

    private double reconcileLagSeconds() {
        try {
            return cursorStore.find(SyncCursor.MEMBER_CHANGES).map(SyncCursor::caughtUpAt)
                    .map(caughtUpAt -> (double) Duration.between(caughtUpAt, LocalDateTime.now(clock)).toSeconds())
                    .orElse(Double.NaN);
        } catch (RuntimeException e) {
            // 지표 수집(scrape)을 실패시키지 않는다. 값이 비면 보는 쪽이 안다
            log.debug("증분 보정 커서를 읽지 못해 지연 지표를 비운다", e);
            return Double.NaN;
        }
    }

    private double consistencyDifference() {
        try {
            LocalDateTime staleBefore = LocalDateTime.now(clock).minus(CONSISTENCY_STALE_AFTER);
            return consistencyChecks.find(ConsistencyCheck.INFLUENCER)
                    .filter(check -> check.checkedAt().isAfter(staleBefore))
                    .map(check -> (double) check.difference())
                    .orElse(Double.NaN);
        } catch (RuntimeException e) {
            log.debug("정합성 점검 결과를 읽지 못해 문서 수 차이 지표를 비운다", e);
            return Double.NaN;
        }
    }
}
