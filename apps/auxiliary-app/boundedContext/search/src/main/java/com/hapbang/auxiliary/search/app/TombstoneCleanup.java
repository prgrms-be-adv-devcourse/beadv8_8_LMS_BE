package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;

import lombok.extern.slf4j.Slf4j;

/**
 * 숨긴 지 7일이 지난 tombstone을 지운다(#771). 7일은 회원 이벤트 DLT 보존 기간이다. 그보다 늦게 다시 넣은 옛 이벤트는 tombstone이
 * 없어 문서를 되살릴 수 있지만, 검색 2(#772)의 증분 보정이 원본과 비교해 다시 숨긴다.
 */
@Slf4j
@Service
public class TombstoneCleanup {

    /** tombstone을 남겨 두는 기간 */
    static final Duration RETENTION = Duration.ofDays(7);

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final SearchJobRunner jobRunner;
    private final Clock clock;

    @Autowired
    public TombstoneCleanup(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store, SearchJobRunner jobRunner) {
        this(indexSetup, store, jobRunner, Clock.systemDefaultZone());
    }

    TombstoneCleanup(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store, SearchJobRunner jobRunner,
            Clock clock) {
        this.indexSetup = indexSetup;
        this.store = store;
        this.jobRunner = jobRunner;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다 */
    public void run() {
        jobRunner.run(SearchJob.TOMBSTONE_CLEANUP, this::cleanup);
    }

    void cleanup() {
        indexSetup.ensureReady();
        Instant hiddenBefore = clock.instant().minus(RETENTION);
        long deleted = store.deleteHiddenBefore(hiddenBefore);
        log.info("오래된 검색 tombstone을 정리했다 deleted={}, hiddenBefore={}", deleted, hiddenBefore);
    }
}
