package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

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
 * 서비스 간 HP 대사 실행 1회 (#581). 어긋난 건은 {@link HpReconciliationMismatch}에 남는다.
 * <p>
 * 실행 중({@code RUNNING})으로 먼저 커밋하고, 지갑 조회·비교가 끝나면 {@code COMPLETED}, 지갑 조회가 실패하면 {@code INCOMPLETE}로 끝낸다.
 */
@Entity
@Table(name = "hp_reconciliation_runs", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HpReconciliationRun extends BaseEntity {

    /** 실패 사유 저장 최대 길이 (DB 컬럼과 같다) */
    static final int FAILURE_REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private HpReconciliationTrigger triggerType;

    /** 수동 실행한 관리자. 배치 실행이면 null */
    private Long triggeredBy;

    @Enumerated(EnumType.STRING)
    private HpReconciliationStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 비교한 요청서·모집글 수 (진행 중이라 뺀 건 제외, DEAD 표시 포함) */
    private int checkedCount;

    /** 어긋난 건 수 (DEAD 표시 제외) */
    private int mismatchCount;

    /** 격리된 HP 작업이 있어 표시만 한 요청서·모집글 수 */
    private int deadCount;

    private String failureReason;

    @Version
    private Long version;

    private HpReconciliationRun(HpReconciliationTrigger triggerType, Long triggeredBy, LocalDateTime now) {
        this.triggerType = triggerType;
        this.triggeredBy = triggeredBy;
        this.status = HpReconciliationStatus.RUNNING;
        this.startedAt = now;
    }

    public static HpReconciliationRun start(HpReconciliationTrigger triggerType, Long triggeredBy, LocalDateTime now) {
        if (triggerType == HpReconciliationTrigger.MANUAL && triggeredBy == null) {
            throw new IllegalArgumentException("수동 실행은 실행한 관리자가 필요합니다.");
        }
        return new HpReconciliationRun(triggerType, triggeredBy, now);
    }

    public void complete(int checkedCount, int mismatchCount, int deadCount, LocalDateTime now) {
        requireRunning();
        this.status = HpReconciliationStatus.COMPLETED;
        this.checkedCount = checkedCount;
        this.mismatchCount = mismatchCount;
        this.deadCount = deadCount;
        this.finishedAt = now;
    }

    /** 끝까지 비교하지 못했다. 그때까지 비교한 결과는 남긴다 */
    public void incomplete(int checkedCount, int mismatchCount, int deadCount, String reason, LocalDateTime now) {
        requireRunning();
        this.status = HpReconciliationStatus.INCOMPLETE;
        this.checkedCount = checkedCount;
        this.mismatchCount = mismatchCount;
        this.deadCount = deadCount;
        this.failureReason = truncate(reason);
        this.finishedAt = now;
    }

    /** 끝내지 못하고 멈춘 실행을 미완료로 끝낸다. 비교 수는 지금까지 기록한 값 그대로 둔다 (#821) */
    public void abandon(String reason, LocalDateTime now) {
        incomplete(checkedCount, mismatchCount, deadCount, reason, now);
    }

    public boolean isRunning() {
        return status == HpReconciliationStatus.RUNNING;
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
