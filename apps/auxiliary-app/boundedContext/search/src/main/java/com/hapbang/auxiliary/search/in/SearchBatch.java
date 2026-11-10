package com.hapbang.auxiliary.search.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;
import com.hapbang.auxiliary.search.app.InfluencerReindexer;
import com.hapbang.auxiliary.search.app.InfluencerStatsSync;
import com.hapbang.auxiliary.search.app.MemberChangeReconciler;
import com.hapbang.auxiliary.search.app.TombstoneCleanup;
import com.hapbang.auxiliary.search.app.ZeroResultKeywords;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 검색 배치(#771, 증분 보정 #772, 처음 적재·재색인 확인 #773). 실행 시각만 정하고 처리는 app 서비스가 한다. 한 pod 실행은 각 서비스가 advisory lock으로 맞춘다.
 */
@Component
@ConditionalOnSearchEnabled
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class SearchBatch {

    private final TombstoneCleanup tombstoneCleanup;
    private final InfluencerStatsSync statsSync;
    private final MemberChangeReconciler memberChangeReconciler;
    private final ZeroResultKeywords zeroResultKeywords;
    private final InfluencerReindexer reindexer;

    /** 결과 0건 검색어를 모아 로그 한 줄로 남긴다. pod마다 따로 센 것을 각자 남긴다(락 없음) */
    @Scheduled(fixedDelayString = "${hapbang.search.zero-result-log-interval:10m}", initialDelayString = "10m")
    void logZeroResultKeywords() {
        zeroResultKeywords.flush();
    }

    /** 통계·분야 이름 동기화. 끝난 뒤 10분 쉬고 다시 한다(겹치지 않게). 기동 직후는 index 준비를 기다려 1분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.stats-sync-interval:10m}", initialDelayString = "1m")
    void syncStats() {
        statsSync.run();
    }

    /** 회원 변경분 증분 보정. 끝난 뒤 10분 쉬고 다시 한다. 통계 동기화와 시작을 1분 어긋나게 2분 뒤에 시작한다 */
    @Scheduled(fixedDelayString = "${hapbang.search.member-reconcile-interval:10m}", initialDelayString = "2m")
    void reconcileMemberChanges() {
        memberChangeReconciler.run();
    }

    /**
     * 처음 적재·재색인이 필요한지 확인하고 필요하면 한다(#773 Q2). 시작 때 실패했거나 lock을 놓친 경우를 사람 없이 다시 한다. 필요 없으면 alias를
     * 한 번 읽고 끝난다. 시작 때 한 번은 {@code SearchIndexStartup}이 하므로 10분 뒤부터 한다.
     */
    @Scheduled(fixedDelayString = "${hapbang.search.reindex-check-interval:10m}", initialDelayString = "10m")
    void checkIndex() {
        reindexer.run();
    }

    /** 7일 지난 tombstone 정리. 하루 1번, 사용이 적은 새벽 */
    @Scheduled(cron = "${hapbang.search.tombstone-cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    void cleanupTombstones() {
        tombstoneCleanup.run();
    }
}
