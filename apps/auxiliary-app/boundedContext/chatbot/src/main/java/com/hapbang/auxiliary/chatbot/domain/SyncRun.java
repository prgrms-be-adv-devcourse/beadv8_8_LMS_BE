package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 싱크 회차 (#716). 종류마다 따로 실행·기록한다. 회차 중 바뀌는 값(건수)은 회차가 끝날 때 한 번에 적는다.
 */
@Entity
@Table(name = "sync_run", schema = "chatbot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncRun {

    /** 오류 컬럼 길이 */
    static final int ERROR_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private SyncTrigger trigger;

    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncRunStatus status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private long scanned;

    private long inserted;

    private long fixed;

    private long oursNewer;

    private long markedMissing;

    /** 마스킹한 회원의 데이터라 버린 건수 (#717) */
    private long discarded;

    private boolean warning;

    private String error;

    private SyncRun(SyncType type, SyncTrigger trigger, Long requestedBy, SyncRunStatus status, LocalDateTime startedAt) {
        this.type = type;
        this.trigger = trigger;
        this.requestedBy = requestedBy;
        this.status = status;
        this.startedAt = startedAt;
    }

    /** 실행을 시작한 회차 */
    public static SyncRun start(SyncType type, SyncTrigger trigger, Long requestedBy, LocalDateTime now) {
        return new SyncRun(type, trigger, requestedBy, SyncRunStatus.RUNNING, now);
    }

    /** 락을 잡지 못해 실행하지 않은 회차 */
    public static SyncRun skipped(SyncType type, SyncTrigger trigger, String reason, LocalDateTime now) {
        SyncRun run = new SyncRun(type, trigger, null, SyncRunStatus.SKIPPED, now);
        run.finishedAt = now;
        run.error = truncate(reason);
        return run;
    }

    /** 끝까지 읽었다 */
    public void succeed(SyncTally tally, long markedMissing, boolean warning, LocalDateTime now) {
        record(tally);
        this.markedMissing = markedMissing;
        this.warning = warning;
        this.status = SyncRunStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    /** 중간에 멈췄다. 그때까지 반영한 건수는 남긴다 */
    public void fail(SyncTally tally, String error, LocalDateTime now) {
        record(tally);
        this.status = SyncRunStatus.FAILED;
        this.error = truncate(error);
        this.finishedAt = now;
    }

    private void record(SyncTally tally) {
        this.scanned = tally.scanned();
        this.inserted = tally.inserted();
        this.fixed = tally.fixed();
        this.oursNewer = tally.oursNewer();
        this.discarded = tally.discarded();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_MAX_LENGTH);
    }
}
