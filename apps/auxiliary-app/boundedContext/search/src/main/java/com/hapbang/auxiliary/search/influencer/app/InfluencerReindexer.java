package com.hapbang.auxiliary.search.influencer.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.Reindexer;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.influencer.out.MemberListSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 인플루언서 검색 index의 처음 적재와 무중단 재색인(#773). 흐름은 공통 {@link Reindexer}가 하고, 여기는 인플루언서 부품을 끼워 넣는다(#868 R1).
 * <ul>
 *     <li>채우기: {@link InfluencerIndexLoader}(회원 목록 + 통계)</li>
 *     <li>따라잡기: {@link MemberChangeReconciler#runFrom}(회원 변경분 증분 보정)</li>
 *     <li>락: {@link SearchJob#REINDEX}, 지표·로그 대상 {@code influencer}</li>
 * </ul>
 */
@Service
public class InfluencerReindexer {

    private final Reindexer reindexer;

    @Autowired
    public InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            InfluencerStatsSync statsSync, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            MeterRegistry registry) {
        this(indexSetup, store, indexer, categoryNames, members, statsSync, reconciler, jobRunner, registry,
                Clock.system(MemberChangeReconciler.ZONE), Reindexer.CATCH_UP_RETRY_INTERVAL,
                Reindexer.CATCH_UP_MAX_WAIT, Instant.now());
    }

    InfluencerReindexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            InfluencerMemberIndexer indexer, ContentCategoryNames categoryNames, MemberListSource members,
            InfluencerStatsSync statsSync, MemberChangeReconciler reconciler, SearchJobRunner jobRunner,
            MeterRegistry registry, Clock clock, Duration retryInterval, Duration maxWait, Instant startedAt) {
        this.reindexer = new Reindexer(InfluencerIndexDefinition.METRIC_TARGET, indexSetup, store.writes(),
                new InfluencerIndexLoader(store, indexer, categoryNames, members, statsSync), reconciler, jobRunner,
                SearchJob.REINDEX, registry, clock, retryInterval, maxWait, startedAt);
    }

    /** 한 pod에서만 실행한다. 다른 pod가 하고 있으면 건너뛰고, 실패하면 다음 확인에 다시 한다 */
    public void run() {
        reindexer.run();
    }

    /** 락 없이 한 번 확인한다. 바깥에서는 {@link #run}만 부른다. 테스트는 같은 패키지에서 바로 부른다 */
    ReindexResult reindexIfNeeded() {
        return reindexer.reindexIfNeeded();
    }
}
