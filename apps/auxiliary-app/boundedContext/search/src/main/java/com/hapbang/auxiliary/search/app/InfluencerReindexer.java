package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.app.MemberChangeReconciler.CatchUp;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.IndexState;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.MemberDocument;
import com.hapbang.auxiliary.search.out.MemberListSource;
import com.hapbang.auxiliary.search.out.SynonymsRejectedException;
import com.hapbang.auxiliary.search.out.WriteOutcome;
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
 * 단, alias가 가리키는 index가 <b>이 pod가 뜬 뒤에 만들어졌으면</b> 건너뛴다(PR #845 리뷰 Q4). 나중에 뜬 다른 배포가 만든 더 새 설정이기 때문이다.
 * 롤링 배포 중 아직 내려가지 않은 옛 pod가 새 index를 옛 설정으로 되돌리지 않고, 일부러 옛 코드로 되돌린 롤백은 새 index보다 나중에 뜨므로 되돌린다.
 * 순서
 * <ol>
 *     <li>남은 index(실패한 지난 시도, 지우지 못한 옛 index)를 지운다.</li>
 *     <li>시각 T를 기록하고, 지금 설정의 새 index를 alias 없이 만든다.</li>
 *     <li>회원 목록(모든 회원)을 끝까지 읽어 bulk로 넣는다(회원 version 조건부 쓰기). 정상 인플루언서는 보이는 문서, 그 밖의 회원은 숨김 문서다
 *     (#773 리뷰 Q3). ES가 그 회원 값 때문에 거절한 회원은 건너뛴다(증분 보정과 같다). 이어서 통계를 끝까지 읽어 채운다.</li>
 *     <li>새 index를 refresh하고, 넣은 정상 인플루언서 수와 보이는 문서 수를 비교한다. 다르면 멈춘다.</li>
 *     <li>alias를 한 요청으로 옮긴다(remove + add). 이 순간까지 검색·이벤트는 옛 index를 쓴다(처음 적재면 검색은 503, 이벤트는 건너뜀).</li>
 *     <li>증분 보정을 T − 1분부터 한 번 돈다({@link MemberChangeReconciler#runFrom}). 그사이 이벤트는 옛 index로 갔으므로 바뀐 회원을 새 index에
 *     반영한다. 정기 보정이 돌고 있으면 끝날 때까지 기다렸다 다시 부른다.</li>
 *     <li>옛 index를 지운다.</li>
 * </ol>
 * 1~5에서 실패하면 alias는 옛 그대로 두고 새 index를 지운다(만들지 못했으면 지울 것이 없다). ERROR 로그와 지표를 남기고, 다음 확인(10분 뒤)에 처음부터 다시 한다. alias 교체
 * 응답만 실패하고 ES에는 적용됐으면(시간 초과) alias를 다시 읽어 교체한 것으로 보고 이어 간다.
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
    static final String SYNONYMS_REJECTED_MARKER = "[SEARCH_SYNONYMS_REJECTED]";

    /**
     * 더 새 배포의 index로 볼 때 두는 여유. index를 만든 시각(ES 서버 시계)과 pod가 뜬 시각(pod 시계)은 서로 다른 시계라 몇 초 어긋날 수 있다
     * (PR #845 재리뷰). 이만큼 넘게 늦게 만든 index만 더 새 배포로 본다
     */
    static final Duration CLOCK_SKEW_MARGIN = Duration.ofMinutes(1);
    static final String CATCH_UP_FAILED_MARKER = "[SEARCH_REINDEX_CATCH_UP_FAILED]";

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final InfluencerMemberIndexer indexer;
    private final ContentCategoryNames categoryNames;
    private final MemberListSource members;
    private final InfluencerStatsSync statsSync;
    private final MemberChangeReconciler reconciler;
    private final SearchJobRunner jobRunner;
    private final SearchSyncMetrics metrics;
    private final Clock clock;
    private final Duration retryInterval;
    private final Duration maxWait;
    /** 이 pod(빈)가 뜬 시각. alias index가 이보다 나중에 만들어졌으면 더 새 배포가 만든 것이다 */
    private final Instant startedAt;

    @Autowired
    public InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            InfluencerStatsSync statsSync, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            SearchSyncMetrics metrics) {
        this(indexSetup, store, indexer, categoryNames, members, statsSync, reconciler, jobRunner, metrics,
                Clock.system(MemberChangeReconciler.ZONE), CATCH_UP_RETRY_INTERVAL, CATCH_UP_MAX_WAIT, Instant.now());
    }

    InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            InfluencerStatsSync statsSync, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            SearchSyncMetrics metrics, Clock clock, Duration retryInterval, Duration maxWait, Instant startedAt) {
        this.indexSetup = indexSetup;
        this.store = store;
        this.indexer = indexer;
        this.categoryNames = categoryNames;
        this.members = members;
        this.statsSync = statsSync;
        this.reconciler = reconciler;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
        this.retryInterval = retryInterval;
        this.maxWait = maxWait;
        this.startedAt = startedAt;
    }

    /** 한 pod에서만 실행한다. 다른 pod가 하고 있으면 건너뛰고, 실패하면 다음 확인에 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.REINDEX, this::reindexIfNeeded);
    }

    /**
     * 필요하면 처음 적재·재색인을 한다. 락 없이 돌므로 바깥에서는 {@link #run}만 부른다. 테스트는 같은 패키지에서 바로 부른다.
     * 적재·비교·교체가 실패하면 그 자리에서 ERROR를 남기고 실패 결과를 돌려준다(다시 던지면 실행기가 같은 예외를 또 남긴다).
     *
     * @return 한 일
     * @throws IllegalStateException 상태를 읽거나 남은 index·새 index를 만들고 지우지 못한 경우(ES에 닿지 못함). 실행기가 WARN을 남긴다
     */
    ReindexResult reindexIfNeeded() {
        // pod가 떠 있는 동안 ES를 새로 만들었을 수 있어 기억과 상관없이 다시 맞춘다
        try {
            indexSetup.resyncSynonyms();
        } catch (SynonymsRejectedException e) {
            // 파일이 틀렸다. 10분마다 같은 이유로 실패하므로 사람이 봐야 한다. 처음 적재 전이면 검색이 계속 503이다(PR #845 재리뷰)
            metrics.synonymsRejected();
            log.error("{} ES가 검색 동의어 묶음을 거절해 처음 적재·재색인을 하지 않는다. synonyms.txt를 고쳐 배포한다",
                    SYNONYMS_REJECTED_MARKER, e);
            return ReindexResult.failed(null, null);
        }
        IndexState state = indexSetup.state();
        if (state.upToDate()) {
            // 지난 재색인이 옛 index를 지우지 못했으면 여기서 지운다
            deleteQuietly(state.leftovers(), "남은");
            return ReindexResult.upToDate();
        }
        Optional<Instant> aliasCreatedAt = state.aliasTargetCreatedAt();
        if (aliasCreatedAt.isPresent() && aliasCreatedAt.get().isAfter(startedAt.plus(CLOCK_SKEW_MARGIN))) {
            // 롤링 배포 중 옛 pod다. 더 새 배포가 옮긴 index를 옛 설정으로 되돌리지 않는다(이 pod는 곧 내려간다)
            log.warn("검색 alias가 이 pod가 뜬 뒤에 만든 index를 가리켜 더 새 배포로 보고 재색인하지 않는다. 10분마다 반복되면 배포를 되돌린 "
                            + "상태인지 확인하고 rollout restart로 pod를 다시 띄운다 alias={}, createdAt={}, podStartedAt={}, expected={}",
                    state.aliasTargets(), aliasCreatedAt.get(), startedAt, state.expectedIndex());
            return ReindexResult.upToDate();
        }
        if (state.aliasTargets().contains(state.expectedIndex())) {
            // 사람이 alias를 손으로 바꾼 경우뿐이다. 지금 설정 index를 지우고 다시 만들 수 없어 멈춘다
            log.error("{} 검색 alias가 지금 설정의 index와 다른 index를 함께 가리켜 재색인하지 않는다. alias가 한 index만 가리키게 고친다 "
                    + "targets={}", FAILED_MARKER, state.aliasTargets());
            return ReindexResult.failed(null, null);
        }
        return reindex(state);
    }

    private ReindexResult reindex(IndexState state) {
        ReindexReason reason = state.aliasMissing() ? ReindexReason.FIRST_LOAD : ReindexReason.SETTINGS_CHANGED;
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long started = System.nanoTime();
        log.info("검색 index를 {} 시작한다 reason={}, from={}, to={}, startedAt={}", reason.description(), reason.tag(),
                state.aliasTargets(), state.expectedIndex(), startedAt);
        String fresh;
        try {
            indexSetup.deleteIndices(state.leftovers());
            fresh = indexSetup.createIndex();
        } catch (RuntimeException e) {
            // 사용자 사전·mapping 오류로 ES가 새 index를 거절하는 경우가 여기다. 10분마다 같은 이유로 실패하므로 사람이 봐야 한다(PR #845 리뷰)
            metrics.reindexed(reason, SearchSyncMetrics.REINDEX_FAILURE, Duration.ofNanos(System.nanoTime() - started), 0);
            log.error("{} 검색 index를 {} 위해 새 index를 준비하지 못해 alias를 그대로 두고 다음 확인 때 다시 한다 reason={}, alias={}, "
                    + "index={}", FAILED_MARKER, reason.description(), reason.tag(), state.aliasTargets(),
                    state.expectedIndex(), e);
            return ReindexResult.failed(reason, null);
        }
        LoadResult loaded = null;
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
            AliasCheck check = loaded == null ? AliasCheck.NOT_SWITCHED : checkAlias(fresh);
            if (check != AliasCheck.SWITCHED) {
                if (check == AliasCheck.NOT_SWITCHED) {
                    deleteQuietly(List.of(fresh), "만들던");
                }
                metrics.reindexed(reason, SearchSyncMetrics.REINDEX_FAILURE, Duration.ofNanos(System.nanoTime() - started),
                        0);
                log.error("{} 검색 index를 {} 못해 alias를 그대로 두고 다음 확인 때 다시 한다 reason={}, alias={}, index={}",
                        FAILED_MARKER, reason.description(), reason.tag(), state.aliasTargets(), fresh, e);
                return ReindexResult.failed(reason, fresh);
            }
            // 교체 응답만 실패하고(시간 초과) ES에는 적용됐다. 새 index를 지우면 alias가 사라지므로 교체한 것으로 보고 이어 간다
            log.warn("검색 alias 교체 응답은 실패했지만 alias가 새 index를 가리켜 이어 간다 index={}", fresh, e);
        }

        CatchUp catchUp = catchUp(startedAt.minus(CATCH_UP_OVERLAP));
        deleteQuietly(state.aliasTargets(), "옛");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        String outcome = catchUp == CatchUp.CAUGHT_UP
                ? SearchSyncMetrics.REINDEX_SUCCESS
                : SearchSyncMetrics.REINDEX_CATCH_UP_FAILED;
        metrics.reindexed(reason, outcome, took, loaded.influencers());
        log.info("검색 index를 {} 끝냈다 reason={}, index={}, read={}, influencers={}, hidden={}, skipped={}, "
                        + "statsFailed={}, catchUp={}, tookMs={}", reason.description(), reason.tag(), fresh,
                loaded.read(), loaded.influencers(), loaded.hidden(), loaded.skipped(), loaded.statsFailed(), catchUp,
                took.toMillis());
        return new ReindexResult(reason, fresh, loaded, catchUp, false);
    }

    /**
     * 실패한 뒤 alias가 새 index로 옮겨졌는지 다시 읽는다. 읽지 못하면 새 index를 지우지 않는다(alias가 붙어 있으면 지울 때 alias도 사라진다).
     * 옮겨지지 않았다면 다음 확인 때 남은 index로 지운다.
     */
    private AliasCheck checkAlias(String index) {
        try {
            return indexSetup.state().aliasTargets().contains(index) ? AliasCheck.SWITCHED : AliasCheck.NOT_SWITCHED;
        } catch (RuntimeException e) {
            log.warn("검색 alias를 다시 읽지 못해 새 index를 지우지 않고 다음 확인에 맡긴다 index={}", index, e);
            return AliasCheck.UNKNOWN;
        }
    }

    /** 실패한 뒤 다시 읽은 alias 상태 */
    private enum AliasCheck {
        /** 새 index를 가리킨다(교체 응답만 실패) */
        SWITCHED,
        /** 옛 그대로다 */
        NOT_SWITCHED,
        /** 읽지 못했다 */
        UNKNOWN
    }

    /** 회원 목록으로 문서를 넣고(정상 인플루언서는 보이게, 그 밖은 숨김), 통계를 채운다 */
    private LoadResult load(String index) {
        refreshCategoryNames();
        long read = 0;
        long influencers = 0;
        long hidden = 0;
        long skipped = 0;
        Long afterId = null;
        do {
            MemberPageDto page = members.members(afterId, PAGE_SIZE);
            List<MemberDocument> documents = new ArrayList<>();
            for (UserDto user : page.items()) {
                try {
                    documents.add(indexer.documentOf(user));
                } catch (IllegalArgumentException e) {
                    // 회원 정보가 빠졌다. 다시 읽어도 같으므로 건너뛴다(증분 보정과 같다)
                    log.warn("회원 정보가 빠져 검색 처음 적재에서 건너뛴다 userId={}", user == null ? null : user.id(), e);
                    skipped++;
                }
            }
            Map<Long, WriteOutcome> outcomes = store.loadMembers(index, documents);
            requireNoFailure(outcomes, index);
            for (MemberDocument document : documents) {
                if (outcomes.get(document.userId()) == WriteOutcome.REJECTED) {
                    // ES가 이 회원의 값 때문에 거절했다. 다시 넣어도 같으므로 건너뛴다(멈추면 처음 적재가 영영 끝나지 않는다)
                    skipped++;
                } else if (document.isVisible()) {
                    influencers++;
                } else {
                    hidden++;
                }
            }
            read += page.items().size();
            afterId = page.nextAfterId();
        } while (afterId != null);

        long statsFailed = statsSync.applyAllStats(index).count(WriteOutcome.FAILED);
        return new LoadResult(read, influencers, hidden, skipped, statsFailed);
    }

    /** 분야 이름을 새로 읽는다. 못 읽으면 가진 이름으로 넣고 10분 동기화가 채운다(통계 동기화와 같다) */
    private void refreshCategoryNames() {
        try {
            categoryNames.refresh();
        } catch (RuntimeException e) {
            log.warn("분야 이름을 읽지 못해 가진 이름으로 처음 적재한다", e);
        }
    }

    /** 넣지 못한 회원이 있으면(장애) 멈춘다. 그 회원이 빠진 채 alias를 옮기면 다음 재색인까지 검색에 보이지 않는다 */
    private static void requireNoFailure(Map<Long, WriteOutcome> outcomes, String index) {
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
     * @param hidden      넣은 숨김 문서 수(정상 인플루언서가 아닌 회원)
     * @param skipped     회원 정보가 빠졌거나 ES가 그 회원 값 때문에 거절해 건너뛴 회원 수
     * @param statsFailed 통계를 쓰지 못한 회원 수(10분 동기화가 다시 쓴다)
     */
    record LoadResult(long read, long influencers, long hidden, long skipped, long statsFailed) {
    }

    /**
     * @param reason  한 이유. 할 일이 없었거나 시작 전에 멈췄으면 null
     * @param index   새 index. 할 일이 없었으면 null
     * @param loaded  넣은 결과. 할 일이 없었거나 실패했으면 null
     * @param catchUp 따라잡기 결과. 할 일이 없었거나 실패했으면 null
     * @param failed  적재·비교·교체가 실패해 alias를 그대로 뒀는지
     */
    record ReindexResult(ReindexReason reason, String index, LoadResult loaded, CatchUp catchUp, boolean failed) {

        /** 할 일이 없었다(설정이 같음, 또는 더 새 배포가 만든 index) */
        static ReindexResult upToDate() {
            return new ReindexResult(null, null, null, null, false);
        }

        static ReindexResult failed(ReindexReason reason, String index) {
            return new ReindexResult(reason, index, null, null, true);
        }

        /** alias를 새 index로 옮겼는지 */
        boolean done() {
            return loaded != null;
        }
    }
}
