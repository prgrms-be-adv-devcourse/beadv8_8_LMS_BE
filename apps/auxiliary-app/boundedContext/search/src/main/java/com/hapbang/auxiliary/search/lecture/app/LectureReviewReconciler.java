package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.LectureSyncSource;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 리뷰 보정(#884 L13). 1시간마다 리뷰 동기화 API를 키셋으로 끝까지 읽어 리뷰 사본을 맞추고, 이어서 모든 강의 문서의 판매자·평점 칸을 사본과 맞춘다
 * ({@link LectureCopyAligner}). 한 pod에서만 돈다({@link SearchJob#LECTURE_REVIEW_RECONCILE}).
 * <ul>
 *     <li><b>사본 맞추기</b>: 리뷰마다 이벤트와 같은 사본 반영({@link LectureReviewCopyStore#apply}, version이 클 때만)을 한다. 원본에 없는 리뷰는
 *     사본에서 지운다(읽는 동안 생긴 리뷰는 빼고).</li>
 *     <li><b>칸 맞추기</b>: 사본을 바꾼 강의만이 아니라 모든 문서를 본다. 놓친 리뷰 이벤트뿐 아니라 평점 칸 경합으로 남은 옛 값(#884 Q1 a)도 이 회차에
 *     바로잡는다.</li>
 *     <li>사본은 alias가 없어도(처음 적재 전) 맞춘다. 칸 맞추기만 건너뛴다(처음 적재가 사본으로 문서를 만든다).</li>
 * </ul>
 */
@Slf4j
@Service
public class LectureReviewReconciler {

    /** 리뷰 동기화 한 페이지 크기(강의 API 상한) */
    static final int PAGE_SIZE = LectureReviewSyncPageDto.MAX_SIZE;

    private final LectureReviewCopyStore reviews;
    private final LectureSyncSource source;
    private final LectureCopyAligner aligner;
    private final SearchJobRunner jobRunner;
    private final LectureSyncMetrics metrics;
    private final Clock clock;

    @Autowired
    public LectureReviewReconciler(LectureReviewCopyStore reviews, LectureSyncSource source,
            LectureCopyAligner aligner, SearchJobRunner jobRunner, LectureSyncMetrics metrics) {
        this(reviews, source, aligner, jobRunner, metrics, Clock.system(SellerIndexer.ZONE));
    }

    LectureReviewReconciler(LectureReviewCopyStore reviews, LectureSyncSource source, LectureCopyAligner aligner,
            SearchJobRunner jobRunner, LectureSyncMetrics metrics, Clock clock) {
        this.reviews = reviews;
        this.source = source;
        this.aligner = aligner;
        this.jobRunner = jobRunner;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다. 실패하면 다음 회차에 처음부터 다시 읽는다 */
    public void run() {
        jobRunner.run(SearchJob.LECTURE_REVIEW_RECONCILE, this::reconcile);
    }

    /**
     * 보정한다. 락 없이 돌므로 바깥에서는 {@link #run}만 부른다. 테스트는 같은 패키지에서 부른다.
     *
     * @throws IndexNotReadyException alias가 없는 경우(처음 적재 전). 사본은 이미 맞췄다
     * @throws IllegalStateException  ES를 읽거나 쓰지 못한 경우
     * @throws com.hapbang.shared.lecture.out.LectureApiCallException 강의 앱을 부르지 못한 경우
     */
    LectureReconcileResult reconcile() {
        long started = System.nanoTime();
        try {
            LectureReconcileResult copied = syncCopies();
            LectureReconcileResult result = copied.plus(aligner.align());
            metrics.reconciled(LectureSyncMetrics.JOB_REVIEW, result, Duration.ofNanos(System.nanoTime() - started));
            log.info("강의 리뷰 보정을 했다 read={}, copied={}, reviewDeleted={}, alreadyApplied={}, documents={}, "
                            + "sellerFixed={}, ratingFixed={}", copied.read(), result.count(LectureReconcileKind.COPIED),
                    result.count(LectureReconcileKind.REVIEW_DELETED),
                    result.count(LectureReconcileKind.ALREADY_APPLIED), result.read() - copied.read(),
                    result.count(LectureReconcileKind.SELLER_FIXED), result.count(LectureReconcileKind.RATING_FIXED));
            return result;
        } catch (IndexNotReadyException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.reconcileFailed(LectureSyncMetrics.JOB_REVIEW, Duration.ofNanos(System.nanoTime() - started));
            throw e;
        }
    }

    /**
     * 리뷰 동기화 API 전체로 리뷰 사본을 맞춘다. 처음 적재·재색인(#884 Q2 a)도 문서를 만들기 전에 부른다.
     *
     * @return 읽은 리뷰 수와 결과별 수({@link LectureReconcileKind#COPIED}, {@link LectureReconcileKind#ALREADY_APPLIED},
     * {@link LectureReconcileKind#REVIEW_DELETED})
     */
    LectureReconcileResult syncCopies() {
        Map<LectureReconcileKind, Long> counts = new EnumMap<>(LectureReconcileKind.class);
        Set<Long> seen = new HashSet<>();
        long read = 0;
        Long maxId = null;
        Long afterId = null;
        do {
            LectureReviewSyncPageDto page = source.reviews(afterId, PAGE_SIZE);
            LocalDateTime now = LocalDateTime.now(clock);
            for (LectureReviewSyncDto review : page.items()) {
                seen.add(review.id());
                counts.merge(apply(review, now), 1L, Long::sum);
            }
            read += page.items().size();
            if (!page.items().isEmpty()) {
                maxId = page.items().getLast().id();
            }
            afterId = page.nextAfterId();
        } while (afterId != null);
        if (maxId != null) {
            List<Long> deleted = reviews.deleteMissing(seen, maxId);
            if (!deleted.isEmpty()) {
                counts.merge(LectureReconcileKind.REVIEW_DELETED, (long) deleted.size(), Long::sum);
                log.info("원본에 없는 리뷰를 리뷰 사본에서 지웠다 count={}, reviewIds={}", deleted.size(), deleted);
            }
        }
        return new LectureReconcileResult(read, counts);
    }

    // 리뷰 ID·강의 ID는 DTO 생성자가 막아 빠질 수 없다
    private LectureReconcileKind apply(LectureReviewSyncDto review, LocalDateTime now) {
        if (!reviews.apply(review, now)) {
            return LectureReconcileKind.ALREADY_APPLIED;
        }
        log.info("리뷰 이벤트를 놓친 리뷰 사본을 보정했다 reviewId={}, lectureId={}, version={}", review.id(),
                review.lectureId(), review.version());
        return LectureReconcileKind.COPIED;
    }
}
