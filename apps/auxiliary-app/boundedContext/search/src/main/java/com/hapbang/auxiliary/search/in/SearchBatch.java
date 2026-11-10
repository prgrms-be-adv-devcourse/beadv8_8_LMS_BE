package com.hapbang.auxiliary.search.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.app.InfluencerStatsSync;
import com.hapbang.auxiliary.search.app.TombstoneCleanup;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 검색 배치(#771). 실행 시각만 정하고 처리는 app 서비스가 한다. 한 pod 실행은 각 서비스가 advisory lock으로 맞춘다.
 */
@Component
@ConditionalOnSearchEnabled
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class SearchBatch {

    private final TombstoneCleanup tombstoneCleanup;
    private final InfluencerStatsSync statsSync;

    /** 통계·분야 이름 동기화. 끝난 뒤 10분 쉬고 다시 한다(겹치지 않게). 기동 직후는 index 준비를 기다려 1분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.stats-sync-interval:10m}", initialDelayString = "1m")
    void syncStats() {
        statsSync.run();
    }

    /** 7일 지난 tombstone 정리. 하루 1번, 사용이 적은 새벽 */
    @Scheduled(cron = "${hapbang.search.tombstone-cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    void cleanupTombstones() {
        tombstoneCleanup.run();
    }
}
