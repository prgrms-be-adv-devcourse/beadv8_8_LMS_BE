package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.Reindexer;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.LectureSyncSource;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.out.MemberListSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 검색 index의 처음 적재와 무중단 재색인(#884). 흐름은 공통 {@link Reindexer}가 하고(#868), 여기는 강의 부품을 끼워 넣는다.
 * <ul>
 *     <li>채우기: {@link LectureIndexLoader}(판매자 사본 → 리뷰 사본 → 강의)</li>
 *     <li>따라잡기: {@link LectureIndexCatchUp}(강의 보정 + 판매자·평점 칸 맞추기)</li>
 *     <li>락: {@link SearchJob#LECTURE_REINDEX}, 지표·로그 대상 {@code lecture}</li>
 * </ul>
 */
@Service
public class LectureReindexer {

    private final Reindexer reindexer;

    @Autowired
    public LectureReindexer(LectureIndexSetup indexSetup, LectureDocumentStore documents,
            SearchMemberCopyStore members, LectureReviewCopyStore reviews, MemberListSource memberList,
            LectureSyncSource lectures, LectureReconciler reconciler, LectureReviewReconciler reviewReconciler,
            LectureCopyAligner aligner, SearchJobRunner jobRunner, MeterRegistry registry) {
        this(indexSetup, documents, members, reviews, memberList, lectures, reconciler, reviewReconciler, aligner,
                jobRunner, registry, Clock.system(SellerIndexer.ZONE), Reindexer.CATCH_UP_RETRY_INTERVAL,
                Reindexer.CATCH_UP_MAX_WAIT, Instant.now());
    }

    LectureReindexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, SearchMemberCopyStore members,
            LectureReviewCopyStore reviews, MemberListSource memberList, LectureSyncSource lectures,
            LectureReconciler reconciler, LectureReviewReconciler reviewReconciler, LectureCopyAligner aligner,
            SearchJobRunner jobRunner, MeterRegistry registry, Clock clock, Duration retryInterval, Duration maxWait,
            Instant startedAt) {
        this.reindexer = new Reindexer(LectureIndexDefinition.METRIC_TARGET, indexSetup, documents.writes(),
                new LectureIndexLoader(documents, members, reviews, memberList, lectures, reviewReconciler, clock),
                new LectureIndexCatchUp(reconciler, aligner, jobRunner), jobRunner, SearchJob.LECTURE_REINDEX,
                registry, clock, retryInterval, maxWait, startedAt);
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
