package com.hapbang.auxiliary.search.lecture.app;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hapbang.auxiliary.search.common.app.IndexCatchUp;
import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 재색인 따라잡기 부품(#884). alias를 새 index로 옮긴 직후, 채우는 동안 바뀐 원본을 새 index에 반영한다. 강의·리뷰에는 변경분 API가 없어
 * 시각 대신 <b>전체를 한 번 비교</b>한다.
 * <ol>
 *     <li>강의 보정({@link LectureReconciler}): 채우는 동안의 강의 이벤트는 옛 index로 갔거나(재색인) 건너뛰었다(처음 적재). 강의 동기화 API 전체와
 *     version을 비교해 다른 것만 쓴다.</li>
 *     <li>판매자·평점 칸 맞추기({@link LectureCopyAligner}): 채우는 동안의 회원·리뷰 이벤트는 사본(DB)은 고쳤지만 새 index 문서는 고치지 못했다.
 *     문서 칸을 사본과 비교해 다른 것만 쓴다.</li>
 * </ol>
 * 강의 정기 보정과 같은 락({@link SearchJob#LECTURE_RECONCILE})이다. 정기 보정이 돌고 있으면 {@link CatchUp#BUSY}를 돌려 공통 재색인이 잠시 뒤 다시
 * 부른다.
 */
@Slf4j
class LectureIndexCatchUp implements IndexCatchUp {

    private final LectureReconciler reconciler;
    private final LectureCopyAligner aligner;
    private final SearchJobRunner jobRunner;

    LectureIndexCatchUp(LectureReconciler reconciler, LectureCopyAligner aligner, SearchJobRunner jobRunner) {
        this.reconciler = reconciler;
        this.aligner = aligner;
        this.jobRunner = jobRunner;
    }

    /** 시각으로 고르지 않고 전체를 비교하므로 겹쳐 읽기가 필요 없다 */
    @Override
    public Duration overlap() {
        return Duration.ZERO;
    }

    /**
     * 강의 보정과 판매자·평점 칸 맞추기를 한 번 돈다.
     *
     * @param from 쓰지 않는다(전체를 비교한다). 로그에만 남긴다
     * @return 끝까지 돌았으면 {@link CatchUp#CAUGHT_UP}, 정기 보정이 락을 쥐고 있으면 {@link CatchUp#BUSY}, 돌다가 실패했으면
     * {@link CatchUp#FAILED}(다음 정기 보정·리뷰 보정이 바로잡는다)
     */
    @Override
    public CatchUp runFrom(LocalDateTime from) {
        AtomicBoolean caughtUp = new AtomicBoolean();
        boolean ran = jobRunner.run(SearchJob.LECTURE_RECONCILE, () -> {
            LectureReconcileResult lectures = reconciler.reconcile();
            LectureReconcileResult fields = aligner.align();
            log.info("강의 검색 재색인 뒤 따라잡기를 했다 startedAt={}, lectureFixed={}, sellerFixed={}, ratingFixed={}", from,
                    lectures.fixed(), fields.count(LectureReconcileKind.SELLER_FIXED),
                    fields.count(LectureReconcileKind.RATING_FIXED));
            caughtUp.set(true);
        });
        if (!ran) {
            return CatchUp.BUSY;
        }
        return caughtUp.get() ? CatchUp.CAUGHT_UP : CatchUp.FAILED;
    }
}
