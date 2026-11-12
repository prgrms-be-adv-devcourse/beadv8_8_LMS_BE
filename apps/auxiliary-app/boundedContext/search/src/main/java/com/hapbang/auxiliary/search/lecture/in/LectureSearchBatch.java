package com.hapbang.auxiliary.search.lecture.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.lecture.app.LectureTombstoneCleanup;

/**
 * 강의 검색 배치(#882). 실행 시각만 정하고 처리는 app 서비스가 한다. 한 pod 실행은 서비스가 advisory lock으로 맞춘다. 보정·처음 적재는 #884에서 더한다.
 */
@Component
@ConditionalOnSchedulingEnabled
class LectureSearchBatch {

    private final LectureTombstoneCleanup tombstoneCleanup;

    LectureSearchBatch(LectureTombstoneCleanup tombstoneCleanup) {
        this.tombstoneCleanup = tombstoneCleanup;
    }

    /** 7일 지난 tombstone 정리. 하루 1번, 사용이 적은 새벽. 인플루언서 정리(04:30)와 10분 어긋나게 한다 */
    @Scheduled(cron = "${hapbang.search.lecture-tombstone-cleanup-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    void cleanupTombstones() {
        tombstoneCleanup.run();
    }
}
