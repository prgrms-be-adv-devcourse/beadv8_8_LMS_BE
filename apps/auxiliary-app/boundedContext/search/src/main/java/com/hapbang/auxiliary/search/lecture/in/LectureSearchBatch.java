package com.hapbang.auxiliary.search.lecture.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.lecture.app.LectureTombstoneCleanup;
import com.hapbang.auxiliary.search.lecture.app.LectureZeroResultKeywords;

/**
 * 강의 검색 배치(#882, 0건 검색어 #883). 실행 시각만 정하고 처리는 app 서비스가 한다. 한 pod 실행은 서비스가 advisory lock으로 맞춘다. 보정·처음 적재는 #884에서 더한다.
 */
@Component
@ConditionalOnSchedulingEnabled
class LectureSearchBatch {

    private final LectureTombstoneCleanup tombstoneCleanup;
    private final LectureZeroResultKeywords zeroResultKeywords;

    LectureSearchBatch(LectureTombstoneCleanup tombstoneCleanup, LectureZeroResultKeywords zeroResultKeywords) {
        this.tombstoneCleanup = tombstoneCleanup;
        this.zeroResultKeywords = zeroResultKeywords;
    }

    /** 결과 0건 검색어를 모아 로그 한 줄로 남긴다. 인플루언서와 같은 주기·설정 키, pod마다 따로 센 것을 각자 남긴다(락 없음, Q4) */
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
