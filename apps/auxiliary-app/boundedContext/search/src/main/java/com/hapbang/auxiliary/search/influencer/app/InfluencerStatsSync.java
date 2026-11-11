package com.hapbang.auxiliary.search.influencer.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.ConsistencyCheck;
import com.hapbang.auxiliary.search.common.out.ConsistencyCheckStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.influencer.out.InfluencerStats;
import com.hapbang.auxiliary.search.influencer.out.MemberStatsSource;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 10분마다 회원 앱 통계로 검색 문서를 맞춘다(#771). 한 pod에서만 돈다({@link SearchJob#STATS_SYNC}).
 * <ol>
 *     <li>분야 이름: 분야 목록을 다시 읽고, 분야가 있는 모든 문서의 이름을 맞춘다(이름이 바뀐 분야, 이름을 모른 채 색인한 분야).</li>
 *     <li>통계: 인플루언서 통계(#769)를 처음부터 끝까지 읽어 채널 이름·대표 채널·팔로워·평점을 부분 갱신한다. 값이 같으면 noop이다.
 *     통계에 있는데 문서가 없는 회원은 건너뛴다(넣는 것은 검색 2 #772의 보정). 숨긴 문서도 건너뛴다.</li>
 * </ol>
 * <b>정합성 점검</b>(#772): 끝까지 읽은 정상 인플루언서 수(원본)와 ES의 보이는 문서 수를 비교해 결과를 DB({@link ConsistencyCheckStore})에 남긴다.
 * 증분 보정으로도 맞지 않는 어긋남을 알려 준다. 세는 동안 회원이 바뀌면 잠깐 다를 수 있다. 세지 못해도 통계 동기화는 실패로 치지 않고, 낡은 결과만 지운다.
 * <p>
 * 프로필 이벤트를 놓친 회원을 바로잡는 일은 증분 보정({@link MemberChangeReconciler}, #772)이 회원 변경분으로 한다. 평점 전체 평균(베이지안 C)은 검색 API가
 * pod마다 계산한다(이 작업은 한 pod에서만 돌아 다른 pod가 값을 받을 수 없다).
 */
@Slf4j
@Service
public class InfluencerStatsSync {

    /** 통계 한 페이지 크기(회원 API 상한) */
    static final int PAGE_SIZE = InfluencerStatsPageDto.MAX_SIZE;

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final MemberStatsSource source;
    private final ContentCategoryNames categoryNames;
    private final SearchJobRunner jobRunner;
    private final ConsistencyCheckStore consistencyChecks;
    private final Clock clock;

    @Autowired
    public InfluencerStatsSync(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store, MemberStatsSource source,
            ContentCategoryNames categoryNames, SearchJobRunner jobRunner, ConsistencyCheckStore consistencyChecks) {
        this(indexSetup, store, source, categoryNames, jobRunner, consistencyChecks,
                Clock.system(MemberChangeReconciler.ZONE));
    }

    InfluencerStatsSync(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store, MemberStatsSource source,
            ContentCategoryNames categoryNames, SearchJobRunner jobRunner, ConsistencyCheckStore consistencyChecks,
            Clock clock) {
        this.indexSetup = indexSetup;
        this.store = store;
        this.source = source;
        this.categoryNames = categoryNames;
        this.jobRunner = jobRunner;
        this.consistencyChecks = consistencyChecks;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다. 실패는 다음 회차에 다시 한다 */
    public void run() {
        jobRunner.run(SearchJob.STATS_SYNC, this::sync);
    }

    /** 맞춘다. 테스트는 락 없이 바로 부른다 */
    public StatsSyncResult sync() {
        StatsSyncResult result;
        try {
            result = syncOnce();
        } catch (RuntimeException e) {
            clearConsistency();
            throw e;
        }
        if (result.consistencyChecked()) {
            saveConsistency(result);
        } else {
            clearConsistency();
        }
        return result;
    }

    /** 점검 결과를 남긴다. 남기지 못해도 통계는 이미 맞췄으므로 실패로 치지 않고, 낡은 결과만 지운다(PR #833 재리뷰) */
    private void saveConsistency(StatsSyncResult result) {
        try {
            consistencyChecks.save(new ConsistencyCheck(ConsistencyCheck.INFLUENCER, result.sourceInfluencers(),
                    result.indexVisible(), LocalDateTime.now(clock)));
        } catch (RuntimeException e) {
            log.warn("정합성 점검 결과를 남기지 못해 지난 결과를 지운다", e);
            clearConsistency();
        }
    }

    /** 이번 회차에 점검하지 못했으니 낡은 결과를 지운다. 지우지 못해도 지표가 25분 뒤 값을 비운다({@link SearchSyncMetrics}) */
    private void clearConsistency() {
        try {
            consistencyChecks.clear(ConsistencyCheck.INFLUENCER);
        } catch (RuntimeException e) {
            log.warn("지난 정합성 점검 결과를 지우지 못했다", e);
        }
    }

    private StatsSyncResult syncOnce() {
        indexSetup.ensureReady();
        long categoryDocuments = syncCategoryNames();
        StatsPass pass = applyAllStats(InfluencerIndexDefinition.ALIAS);
        long indexVisible = countVisible();

        StatsSyncResult result = new StatsSyncResult(pass.pages(), pass.count(WriteOutcome.UPDATED),
                pass.count(WriteOutcome.NOOP), pass.count(WriteOutcome.MISSING), pass.count(WriteOutcome.FAILED),
                categoryDocuments, pass.sourceInfluencers(), indexVisible);
        log.info("검색 통계를 맞췄다 pages={}, updated={}, noop={}, missing={}, failed={}, categoryDocuments={}, "
                        + "sourceInfluencers={}, indexVisible={}", result.pages(), result.updated(), result.noop(),
                result.missing(), result.failed(), result.categoryDocuments(), result.sourceInfluencers(),
                result.indexVisible());
        return result;
    }

    /**
     * 인플루언서 통계를 처음부터 끝까지 읽어 이 index(또는 alias)에 부분 갱신한다. 10분 동기화(alias)와 처음 적재·재색인(alias 없는 새 index, #773)이
     * 함께 쓴다. 락 없이 돈다.
     *
     * @param target alias 또는 새 index 이름
     */
    StatsPass applyAllStats(String target) {
        Map<WriteOutcome, Long> counts = new EnumMap<>(WriteOutcome.class);
        Long afterId = null;
        int pages = 0;
        long sourceInfluencers = 0;
        do {
            InfluencerStatsPageDto page = source.influencerStats(afterId, PAGE_SIZE);
            store.applyStats(target, page.items().stream().map(InfluencerStats::from).toList())
                    .values().forEach(outcome -> counts.merge(outcome, 1L, Long::sum));
            sourceInfluencers += page.items().size();
            afterId = page.nextAfterId();
            pages++;
        } while (afterId != null);
        return new StatsPass(pages, sourceInfluencers, counts);
    }

    /**
     * 보이는 문서 수를 센다. 세지 못해도 통계는 이미 맞췄으므로 실패로 치지 않는다.
     *
     * @return 보이는 문서 수. 세지 못했으면 -1
     */
    private long countVisible() {
        try {
            return store.countVisible();
        } catch (RuntimeException e) {
            log.warn("보이는 검색 문서 수를 세지 못해 정합성 점검을 건너뛴다", e);
            return -1;
        }
    }

    /**
     * 분야 이름을 맞춘다. 분야 목록을 읽지 못하거나 쓰지 못해도 통계 동기화는 계속한다(팔로워·평점이 분야 목록 장애에 묶이지 않게).
     *
     * @return 고친 문서 수. 건너뛰었으면 -1
     */
    private long syncCategoryNames() {
        try {
            return store.syncCategoryNames(categoryNames.refresh());
        } catch (RuntimeException e) {
            log.warn("분야 이름을 맞추지 못해 건너뛰고 통계는 계속 맞춘다", e);
            return -1;
        }
    }

    /**
     * 통계 한 바퀴의 결과
     *
     * @param pages             읽은 통계 페이지 수
     * @param sourceInfluencers 원본(통계 API)의 정상 인플루언서 수
     * @param counts            쓰기 결과별 회원 수
     */
    record StatsPass(int pages, long sourceInfluencers, Map<WriteOutcome, Long> counts) {

        StatsPass {
            counts = Map.copyOf(counts);
        }

        long count(WriteOutcome outcome) {
            return counts.getOrDefault(outcome, 0L);
        }
    }

    /**
     * @param pages             읽은 통계 페이지 수
     * @param updated           통계가 바뀐 문서 수
     * @param noop              통계가 같았거나 숨긴 문서 수
     * @param missing           문서가 없어 건너뛴 회원 수(검색 2 보정 대상)
     * @param failed            쓰지 못한 회원 수(다음 회차에 다시)
     * @param categoryDocuments 분야 이름을 고친 문서 수. 분야 이름 맞추기를 건너뛰었으면 -1
     * @param sourceInfluencers 원본(통계 API)의 정상 인플루언서 수
     * @param indexVisible      ES의 보이는 문서 수. 세지 못했으면 -1
     */
    public record StatsSyncResult(int pages, long updated, long noop, long missing, long failed,
                                  long categoryDocuments, long sourceInfluencers, long indexVisible) {

        /** 정합성 점검을 했는지(보이는 문서 수를 셌는지) */
        public boolean consistencyChecked() {
            return indexVisible >= 0;
        }

        /**
         * 원본 − index. 양수면 index에 빠진 인플루언서가 있고(승급을 놓침), 음수면 보이면 안 되는 문서가 있다(강등을 놓침).
         *
         * @throws IllegalStateException 점검하지 못했을 때
         */
        public long difference() {
            if (!consistencyChecked()) {
                throw new IllegalStateException("정합성 점검을 하지 못했습니다.");
            }
            return sourceInfluencers - indexVisible;
        }
    }
}
