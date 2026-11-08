package com.hapbang.lecture.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 간 HP 대사 실행 1회 (#580, D74). 어긋난 건은 {@link LectureHpReconciliationMismatch}에 남는다.
 * <p>
 * 실행 중({@code RUNNING})으로 먼저 커밋하고, 끝까지 비교하면 {@code COMPLETED}, 지갑 조회가 실패하면 {@code INCOMPLETE}로 끝낸다.
 * 오래된 기록은 지우지 않는다.
 */
@Entity
@Table(name = "lecture_hp_reconciliation_run", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureHpReconciliationRun {

    /** 실패 사유 저장 최대 길이 (DB 컬럼과 같다) */
    static final int FAILURE_REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureHpReconciliationTrigger triggerType;

    /** 수동 실행한 관리자. 배치 실행이면 null */
    private Long triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureHpReconciliationStatus status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 비교한 주문 항목 수 (진행 중이라 뺀 건 제외, DEAD 표시·지갑에만 있는 보류 포함) */
    @Column(nullable = false)
    private int checkedCount;

    /** 어긋난 건 수 (DEAD 표시 제외) */
    @Column(nullable = false)
    private int mismatchCount;

    /** 자동 처리가 멈춘 HP 작업이 있어 표시만 한 주문 항목 수 */
    @Column(nullable = false)
    private int deadCount;

    @Column(length = FAILURE_REASON_MAX)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private long version;

    private LectureHpReconciliationRun(LectureHpReconciliationTrigger triggerType, Long triggeredBy,
                                       LocalDateTime now) {
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.status = LectureHpReconciliationStatus.RUNNING;
        this.startedAt = now;
    }

    public static LectureHpReconciliationRun start(LectureHpReconciliationTrigger triggerType, Long triggeredBy,
                                                   LocalDateTime now) {
        if (triggerType == LectureHpReconciliationTrigger.MANUAL && triggeredBy == null) {
            throw new IllegalArgumentException("수동 실행은 실행한 관리자가 필요합니다.");
        }
        return new LectureHpReconciliationRun(triggerType, triggeredBy, now);
    }

    public void complete(int checkedCount, int mismatchCount, int deadCount, LocalDateTime now) {
        requireRunning();
        this.status = LectureHpReconciliationStatus.COMPLETED;
        record(checkedCount, mismatchCount, deadCount, now);
    }

    /** 끝까지 비교하지 못했다. 그때까지 비교한 결과는 남긴다 */
    public void incomplete(int checkedCount, int mismatchCount, int deadCount, String reason, LocalDateTime now) {
        requireRunning();
        this.status = LectureHpReconciliationStatus.INCOMPLETE;
        this.failureReason = truncate(reason);
        record(checkedCount, mismatchCount, deadCount, now);
    }

    public boolean isRunning() {
        return status == LectureHpReconciliationStatus.RUNNING;
    }

    private void record(int checkedCount, int mismatchCount, int deadCount, LocalDateTime now) {
        this.checkedCount = checkedCount;
        this.mismatchCount = mismatchCount;
        this.deadCount = deadCount;
        this.finishedAt = now;
    }

    private void requireRunning() {
        if (!isRunning()) {
            throw new IllegalStateException("실행 중인 대사가 아닙니다: id=" + id + ", status=" + status);
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAILURE_REASON_MAX) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX);
    }
}
