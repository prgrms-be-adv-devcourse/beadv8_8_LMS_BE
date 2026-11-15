package com.hapbang.auxiliary.search.common.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.hapbang.auxiliary.search.common.out.DocumentWrites;
import com.hapbang.auxiliary.search.common.out.IndexSetup;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색 index 하나에서 숨긴 지 7일이 지난 tombstone을 지운다(#771, 공통 코드 #868). 7일은 원본 이벤트 DLT 보존 기간이다. 그보다 늦게 다시 넣은
 * 옛 이벤트는 tombstone이 없어 문서를 되살릴 수 있지만, 대상의 보정이 원본과 비교해 다시 숨긴다.
 * <p>
 * 대상마다 설정(index·락)만 채운 하위 클래스를 빈으로 둔다(예: {@code InfluencerTombstoneCleanup}, #868 R7). 하위 클래스는 생성자만 둔다.
 */
@Slf4j
public class TombstoneCleanup {

    /** tombstone을 남겨 두는 기간 */
    static final Duration RETENTION = Duration.ofDays(7);

    private final IndexSetup indexSetup;
    private final DocumentWrites writes;
    private final SearchJobRunner jobRunner;
    private final SearchJob job;
    private final Clock clock;

    /**
     * @param job 이 대상의 정리 락
     */
    public TombstoneCleanup(IndexSetup indexSetup, DocumentWrites writes, SearchJobRunner jobRunner, SearchJob job,
            Clock clock) {
        this.indexSetup = indexSetup;
        this.writes = writes;
        this.jobRunner = jobRunner;
        this.job = job;
        this.clock = clock;
    }

    /** 한 pod에서만 실행한다 */
    public void run() {
        jobRunner.run(job, this::cleanup);
    }

    void cleanup() {
        indexSetup.ensureReady();
        Instant hiddenBefore = clock.instant().minus(RETENTION);
        long deleted = writes.deleteHiddenBefore(hiddenBefore);
        log.info("오래된 검색 tombstone을 정리했다 alias={}, deleted={}, hiddenBefore={}", writes.alias(), deleted,
                hiddenBefore);
    }
}
