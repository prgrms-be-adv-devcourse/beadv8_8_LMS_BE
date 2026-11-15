package com.hapbang.auxiliary.notification.app;

import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 Backfill 실행 한 번의 상태(ND-092). Redis에 7일 둔다.
 *
 * @param runId       실행 ID
 * @param status      상태
 * @param startedAt   시작 시각
 * @param updatedAt   마지막 갱신 시각(실행 중에는 페이지마다)
 * @param finishedAt  끝난 시각. 실행 중이면 없다
 * @param result      완료 결과. 완료가 아니면 없다
 * @param failureCode 실패 코드. 실패가 아니면 없다
 */
public record BackfillRun(String runId, BackfillRunStatus status, Instant startedAt, Instant updatedAt,
                          Instant finishedAt, BackfillResult result, String failureCode) {

    /** 갱신이 이보다 오래 없으면 RUNNING을 UNKNOWN으로 보인다 */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    public static BackfillRun running(String runId, Instant now) {
        return new BackfillRun(runId, BackfillRunStatus.RUNNING, now, now, null, null, null);
    }

    public BackfillRun touched(Instant now) {
        return new BackfillRun(runId, status, startedAt, now, null, null, null);
    }

    public BackfillRun completed(BackfillResult result, Instant now) {
        return new BackfillRun(runId, BackfillRunStatus.COMPLETED, startedAt, now, now, result, null);
    }

    public BackfillRun failed(String failureCode, Instant now) {
        return new BackfillRun(runId, BackfillRunStatus.FAILED, startedAt, now, now, null, failureCode);
    }

    /** 조회 때 보일 상태: RUNNING인데 갱신이 {@link #STALE_AFTER}보다 오래 없으면 UNKNOWN */
    public BackfillRun viewedAt(Instant now) {
        if (status == BackfillRunStatus.RUNNING && updatedAt.plus(STALE_AFTER).isBefore(now)) {
            return new BackfillRun(runId, BackfillRunStatus.UNKNOWN, startedAt, updatedAt, null, null, null);
        }
        return this;
    }
}
