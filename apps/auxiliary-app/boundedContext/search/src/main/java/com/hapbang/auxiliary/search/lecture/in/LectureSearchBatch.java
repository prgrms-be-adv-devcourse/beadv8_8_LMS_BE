package com.hapbang.auxiliary.search.lecture.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.lecture.app.LectureReconciler;
import com.hapbang.auxiliary.search.lecture.app.LectureReviewReconciler;
import com.hapbang.auxiliary.search.lecture.app.LectureTombstoneCleanup;
import com.hapbang.auxiliary.search.lecture.app.LectureZeroResultKeywords;
import com.hapbang.auxiliary.search.lecture.app.SellerChangeReconciler;

/**
 * 강의 검색 배치(#882, 0건 검색어 #883, 보정 #884). 실행 시각만 정하고 처리는 app 서비스가 한다. 한 pod 실행은 서비스가 advisory lock으로 맞춘다.
 * <p>
 * 보정은 인플루언서 증분 보정처럼 앱 공용 스케줄러 스레드에서 돈다. 평소에는 원본 읽기와 비교뿐이라 수 초이고, 처음 적재처럼 몇 분 걸리는 일은 아니다.
 */
@Component
@ConditionalOnSchedulingEnabled
class LectureSearchBatch {

    private final LectureTombstoneCleanup tombstoneCleanup;
    private final LectureZeroResultKeywords zeroResultKeywords;
    private final LectureReconciler lectureReconciler;
    private final LectureReviewReconciler reviewReconciler;
    private final SellerChangeReconciler sellerReconciler;

    LectureSearchBatch(LectureTombstoneCleanup tombstoneCleanup, LectureZeroResultKeywords zeroResultKeywords,
            LectureReconciler lectureReconciler, LectureReviewReconciler reviewReconciler,
            SellerChangeReconciler sellerReconciler) {
        this.tombstoneCleanup = tombstoneCleanup;
        this.zeroResultKeywords = zeroResultKeywords;
        this.lectureReconciler = lectureReconciler;
        this.reviewReconciler = reviewReconciler;
        this.sellerReconciler = sellerReconciler;
    }

    /** 강의 보정(L13). 끝난 뒤 10분 쉬고 다시 한다. 인플루언서 배치(1·2분)와 시작을 어긋나게 3분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.lecture-reconcile-interval:10m}", initialDelayString = "3m")
    void reconcileLectures() {
        lectureReconciler.run();
    }

    /** 판매자 사본 증분 보정(Q3 a). 끝난 뒤 10분 쉬고 다시 한다. 4분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.seller-reconcile-interval:10m}", initialDelayString = "4m")
    void reconcileSellers() {
        sellerReconciler.run();
    }

    /** 리뷰 보정과 판매자·평점 칸 맞추기(L13, Q1 a). 끝난 뒤 1시간 쉬고 다시 한다. 5분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.lecture-review-reconcile-interval:1h}", initialDelayString = "5m")
    void reconcileReviews() {
        reviewReconciler.run();
    }

    /** 결과 0건 검색어를 모아 로그 한 줄로 남긴다. 인플루언서와 같은 주기·설정 키, pod마다 따로 센 것을 각자 남긴다(락 없음) */
    @Scheduled(fixedDelayString = "${hapbang.search.zero-result-log-interval:10m}", initialDelayString = "10m")
    void logZeroResultKeywords() {
        zeroResultKeywords.flush();
    }

    /** 7일 지난 tombstone 정리. 하루 1번, 사용이 적은 새벽. 인플루언서 정리(04:30)와 10분 어긋나게 한다 */
    @Scheduled(cron = "${hapbang.search.lecture-tombstone-cleanup-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    void cleanupTombstones() {
        tombstoneCleanup.run();
    }
}
