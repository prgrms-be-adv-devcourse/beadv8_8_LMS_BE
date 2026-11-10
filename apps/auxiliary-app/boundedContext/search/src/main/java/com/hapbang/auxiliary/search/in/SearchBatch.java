package com.hapbang.auxiliary.search.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
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

    /** 7일 지난 tombstone 정리. 하루 1번, 사용이 적은 새벽 */
    @Scheduled(cron = "${hapbang.search.tombstone-cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    void cleanupTombstones() {
        tombstoneCleanup.run();
    }
}
