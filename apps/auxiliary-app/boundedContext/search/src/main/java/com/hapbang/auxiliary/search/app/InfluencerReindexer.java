package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.MemberChangeReconciler.CatchUp;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.IndexState;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.InfluencerStats;
import com.hapbang.auxiliary.search.out.MemberDocument;
import com.hapbang.auxiliary.search.out.MemberListSource;
import com.hapbang.auxiliary.search.out.MemberStatsSource;
import com.hapbang.auxiliary.search.out.WriteOutcome;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 처음 적재와 무중단 재색인(#773). 사람이 손대지 않아도 앱 시작 때와 10분마다 확인해(#773 Q2), 아래 둘 중 하나면 한다. 한 pod에서만 돈다
 * ({@link SearchJob#REINDEX}).
 * <ul>
 *     <li>alias가 없다(처음 배포, ES를 새로 만듦): 처음 적재</li>
 *     <li>alias가 다른 설정 해시의 index를 가리킨다(mapping·분석기·사용자 사전이 바뀜): 재색인. 동의어는 해시에 없어 바꿔도 재색인하지 않는다</li>
 * </ul>
 * 순서
 * <ol>
 *     <li>남은 index(실패한 지난 시도, 지우지 못한 옛 index)를 지운다.</li>
 *     <li>시각 T를 기록하고, 지금 설정의 새 index를 alias 없이 만든다.</li>
 *     <li>회원 목록(모든 회원)을 끝까지 읽어 정상 인플루언서를 bulk로 넣는다(회원 version 조건부 쓰기). 이어서 통계를 끝까지 읽어 채운다.</li>
 *     <li>새 index를 refresh하고, 넣은 정상 인플루언서 수와 보이는 문서 수를 비교한다. 다르면 멈춘다.</li>
 *     <li>alias를 한 요청으로 옮긴다(remove + add). 이 순간까지 검색·이벤트는 옛 index를 쓴다(처음 적재면 검색은 503, 이벤트는 건너뜀).</li>
 *     <li>증분 보정을 T − 1분부터 한 번 돈다({@link MemberChangeReconciler#runFrom}). 그사이 이벤트는 옛 index로 갔으므로 바뀐 회원을 새 index에
 *     반영한다. 정기 보정이 돌고 있으면 끝날 때까지 기다렸다 다시 부른다.</li>
 *     <li>옛 index를 지운다.</li>
 * </ol>
 * 3~5에서 실패하면 alias는 옛 그대로 두고 새 index를 지운다. ERROR 로그와 지표를 남기고, 다음 확인(10분 뒤)에 처음부터 다시 한다.
 * <p>
 * 새 index에는 숨김 문서(tombstone)를 넣지 않는다. 재색인 전에 쌓인 DLT 메시지를 되돌리면 숨겨야 할 회원이 다시 보일 수 있지만, 재색인이 원본 전체를
 * 다시 읽었으므로 그 DLT는 되돌릴 필요가 없다(모듈 문서).
 */
@Slf4j
@Service
@ConditionalOnSearchEnabled
public class InfluencerReindexer {

    /** 회원 목록·통계 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = MemberPageDto.MAX_SIZE;

    /** 따라잡기를 T보다 이만큼 앞에서 시작한다. 늦게 커밋된 변경을 놓치지 않게 증분 보정의 겹쳐 읽기와 같은 값이다 */
    static final Duration CATCH_UP_OVERLAP = MemberChangeReconciler.OVERLAP;

    /** 정기 보정이 락을 쥐고 있을 때 다시 부르는 간격과 최대로 기다리는 시간 */
    static final Duration CATCH_UP_RETRY_INTERVAL = Duration.ofSeconds(5);
    static final Duration CATCH_UP_MAX_WAIT = Duration.ofMinutes(5);

    /** 사람이 봐야 하는 실패의 로그 표식({@code docs/dev/logging.md} alert 표식) */
    static final String FAILED_MARKER = "[SEARCH_REINDEX_FAILED]";
    static final String CATCH_UP_FAILED_MARKER = "[SEARCH_REINDEX_CATCH_UP_FAILED]";

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final InfluencerMemberIndexer indexer;
    private final ContentCategoryNames categoryNames;
    private final MemberListSource members;
    private final MemberStatsSource stats;
    private final MemberChangeReconciler reconciler;
    private final SearchJobRunner jobRunner;
    private final SearchSyncMetrics metrics;
    private final Clock clock;
    private final Duration retryInterval;
    private final Duration maxWait;

    @Autowired
    public InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            MemberStatsSource stats, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            SearchSyncMetrics metrics) {
        this(indexSetup, store, indexer, categoryNames, members, stats, reconciler, jobRunner, metrics,
                Clock.system(MemberChangeReconciler.ZONE), CATCH_UP_RETRY_INTERVAL, CATCH_UP_MAX_WAIT);
    }

    InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            MemberStatsSource stats, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            SearchSyncMetrics metrics, Clock clock, Duration retryInterval, Duration maxWait) {
        this.indexSetup = indexSetup;
        this.store = store;
        this.indexer = indexer;
        this.categoryNames = categoryNames;
        this.members = members;
        this.stats = stats;
        this.reconciler = reconciler;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
        this.retryInterval = retryInterval;
        this.maxWait = maxWait;
    }

    /** 한 pod에서만 실행한다. 다른 pod가 하고 있으면 건너뛰고, 실패하면 다음 확인에 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.REINDEX, this::reindexIfNeeded);
    }

    /**
     * 필요하면 처음 적재·재색인을 한다. 락 없이 돌므로 바깥에서는 {@link #run}만 부른다. 테스트는 같은 패키지에서 바로 부른다.
     *
     * @return 한 일
     * @throws IllegalStateException 3~5에서 실패한 경우(alias는 옛 그대로, 새 index는 지웠다). ES·회원 앱에 닿지 못한 경우
     */
    ReindexResult reindexIfNeeded() {
        indexSetup.syncSynonyms();
        IndexState state = indexSetup.state();
        if (state.upToDate()) {
            // 지난 재색인이 옛 index를 지우지 못했으면 여기서 지운다
            deleteQuietly(state.leftovers(), "남은");
            return ReindexResult.upToDate();
        }
        if (state.aliasTargets().contains(state.expectedIndex())) {
            // 사람이 alias를 손으로 바꾼 경우뿐이다. 지금 설정 index를 지우고 다시 만들 수 없어 멈춘다
            log.error("{} 검색 alias가 지금 설정의 index와 다른 index를 함께 가리켜 재색인하지 않는다 targets={}", FAILED_MARKER,
                    state.aliasTargets());
            throw new IllegalStateException("검색 alias가 지금 설정의 index와 다른 index를 함께 가리킵니다. 사람이 확인해야 합니다 targets="
                    + state.aliasTargets());
        }
        return reindex(state);
    }

    private ReindexResult reindex(IndexState state) {
        ReindexReason reason = state.aliasMissing() ? ReindexReason.FIRST_LOAD : ReindexReason.SETTINGS_CHANGED;
        indexSetup.deleteIndices(state.leftovers());

        LocalDateTime startedAt = LocalDateTime.now(clock);
        long started = System.nanoTime();
        log.info("검색 index를 {} 시작한다 reason={}, from={}, to={}, startedAt={}", reason.description(), reason.tag(),
                state.aliasTargets(), state.expectedIndex(), startedAt);
        String fresh = indexSetup.createIndex();
        LoadResult loaded;
        try {
            loaded = load(fresh);
            indexSetup.refresh(fresh);
            long visible = store.countVisible(fresh);
            if (visible != loaded.influencers()) {
                throw new IllegalStateException("새 index의 문서 수가 원본과 다릅니다 index=" + fresh + ", source="
                        + loaded.influencers() + ", index=" + visible);
            }
            indexSetup.switchAlias(state.aliasTargets(), fresh);
        } catch (RuntimeException e) {
            deleteQuietly(List.of(fresh), "만들던");
            metrics.reindexed(reason, SearchSyncMetrics.REINDEX_FAILURE, Duration.ofNanos(System.nanoTime() - started), 0);
            log.error("{} 검색 index를 {} 못해 alias를 그대로 두고 다음 확인 때 다시 한다 reason={}, alias={}, index={}",
                    FAILED_MARKER, reason.description(), reason.tag(), state.aliasTargets(), fresh, e);
            throw e;
        }

        CatchUp catchUp = catchUp(startedAt.minus(CATCH_UP_OVERLAP));
        deleteQuietly(state.aliasTargets(), "옛");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        String outcome = catchUp == CatchUp.CAUGHT_UP
                ? SearchSyncMetrics.REINDEX_SUCCESS
                : SearchSyncMetrics.REINDEX_CATCH_UP_FAILED;
        metrics.reindexed(reason, outcome, took, loaded.influencers());
        log.info("검색 index를 {} 끝냈다 reason={}, index={}, read={}, influencers={}, skipped={}, statsFailed={}, "
                        + "catchUp={}, tookMs={}", reason.description(), reason.tag(), fresh, loaded.read(),
                loaded.influencers(), loaded.skipped(), loaded.statsFailed(), catchUp, took.toMillis());
        return new ReindexResult(reason, fresh, loaded, catchUp);
    }

    /** 회원 목록으로 정상 인플루언서를 넣고, 통계를 채운다 */
    private LoadResult load(String index) {
        refreshCategoryNames();
        long read = 0;
        long influencers = 0;
        long skipped = 0;
        Long afterId = null;
        do {
            MemberPageDto page = members.members(afterId, PAGE_SIZE);
            List<MemberDocument> documents = new ArrayList<>();
            for (UserDto user : page.items()) {
                try {
                    indexer.documentOf(user).ifPresent(documents::add);
                } catch (IllegalArgumentException e) {
                    // 회원 정보가 빠졌다. 다시 읽어도 같으므로 건너뛴다(증분 보정과 같다)
                    log.warn("회원 정보가 빠져 검색 처음 적재에서 건너뛴다 userId={}", user == null ? null : user.id(), e);
                    skipped++;
                }
            }
            requireAllWritten(store.loadMembers(index, documents), index);
            read += page.items().size();
            influencers += documents.size();
            afterId = page.nextAfterId();
        } while (afterId != null);

        long statsFailed = 0;
        Long statsAfterId = null;
        do {
            InfluencerStatsPageDto page = stats.influencerStats(statsAfterId, PAGE_SIZE);
            statsFailed += store.applyStats(index, page.items().stream().map(InfluencerStats::from).toList())
                    .values().stream().filter(outcome -> outcome == WriteOutcome.FAILED).count();
            statsAfterId = page.nextAfterId();
        } while (statsAfterId != null);
        return new LoadResult(read, influencers, skipped, statsFailed);
    }

    /** 분야 이름을 새로 읽는다. 못 읽으면 가진 이름으로 넣고 10분 동기화가 채운다(통계 동기화와 같다) */
    private void refreshCategoryNames() {
        try {
            categoryNames.refresh();
        } catch (RuntimeException e) {
            log.warn("분야 이름을 읽지 못해 가진 이름으로 처음 적재한다", e);
        }
    }

    /** 넣지 못한 회원이 있으면 멈춘다. 그 회원이 빠진 채 alias를 옮기면 다음 재색인까지 검색에 보이지 않는다 */
    private static void requireAllWritten(Map<Long, WriteOutcome> outcomes, String index) {
        List<Long> failed = outcomes.entrySet().stream().filter(e -> e.getValue() == WriteOutcome.FAILED)
                .map(Map.Entry::getKey).sorted().toList();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("새 index에 회원을 넣지 못했습니다 index=" + index + ", userIds=" + failed);
        }
    }

    /**
     * T − 1분부터 따라잡는다. 정기 보정이 락을 쥐고 있으면 끝날 때까지 기다렸다 다시 부른다(돌지 못하면 재색인 중 바뀐 회원을 놓친다).
     * 실패하면 커서가 그 시각 뒤에 있어 다음 정기 보정이 이어 가므로 ERROR만 남긴다.
     */
    private CatchUp catchUp(LocalDateTime from) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        CatchUp result = reconciler.runFrom(from);
        while (result == CatchUp.BUSY && System.nanoTime() - deadline < 0) {
            sleep(retryInterval);
            result = reconciler.runFrom(from);
        }
        if (result == CatchUp.BUSY) {
            log.error("{} 정기 보정이 끝나지 않아 재색인 뒤 따라잡기를 하지 못했다. 재색인 중 바뀐 회원이 새 index에 빠졌을 수 있다 from={}",
                    CATCH_UP_FAILED_MARKER, from);
        } else if (result == CatchUp.FAILED) {
            log.error("{} 재색인 뒤 따라잡기가 실패했다. 다음 정기 보정이 이어 간다 from={}", CATCH_UP_FAILED_MARKER, from);
        }
        return result;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("따라잡기를 기다리다 중단됐습니다", e);
        }
    }

    /** 지우지 못해도 멈추지 않는다. 남은 index는 다음 확인 때 지운다 */
    private void deleteQuietly(Collection<String> indices, String what) {
        try {
            indexSetup.deleteIndices(indices);
        } catch (RuntimeException e) {
            log.warn("{} 검색 index를 지우지 못해 다음 확인 때 다시 지운다 indices={}", what, indices, e);
        }
    }

    /** 처음 적재·재색인을 한 이유 */
    public enum ReindexReason {
        /** alias가 없었다 */
        FIRST_LOAD("first_load", "처음 적재"),
        /** 설정 해시가 달랐다 */
        SETTINGS_CHANGED("settings_changed", "재색인");

        private final String tag;
        private final String description;

        ReindexReason(String tag, String description) {
            this.tag = tag;
            this.description = description;
        }

        /** 지표·로그 값 */
        public String tag() {
            return tag;
        }

        String description() {
            return description;
        }
    }

    /**
     * @param read        읽은 회원 수(모든 역할·상태)
     * @param influencers 넣은 정상 인플루언서 수(문서 수 비교의 원본 수)
     * @param skipped     회원 정보가 빠져 건너뛴 회원 수
     * @param statsFailed 통계를 쓰지 못한 회원 수(10분 동기화가 다시 쓴다)
     */
    record LoadResult(long read, long influencers, long skipped, long statsFailed) {
    }

    /**
     * @param reason  한 이유. 할 일이 없었으면 null
     * @param index   새 index. 할 일이 없었으면 null
     * @param loaded  넣은 결과. 할 일이 없었으면 null
     * @param catchUp 따라잡기 결과. 할 일이 없었으면 null
     */
    record ReindexResult(ReindexReason reason, String index, LoadResult loaded, CatchUp catchUp) {

        static ReindexResult upToDate() {
            return new ReindexResult(null, null, null, null);
        }

        boolean done() {
            return reason != null;
        }
    }
}
