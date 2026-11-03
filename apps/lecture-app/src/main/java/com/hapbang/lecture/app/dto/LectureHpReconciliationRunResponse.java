package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.lecture.domain.LectureHpReconciliationMismatch;
import com.hapbang.lecture.domain.LectureHpReconciliationRun;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;
import com.hapbang.lecture.domain.LectureHpReconciliationTrigger;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 HP 대사 실행 1회와 어긋난 건 (관리자, D74)")
public record LectureHpReconciliationRunResponse(
        Long id,
        LectureHpReconciliationTrigger triggerType,
        @Schema(description = "수동 실행한 관리자 회원 ID. 배치면 null", nullable = true)
        Long triggeredBy,
        LectureHpReconciliationStatus status,
        @Schema(description = "시작 시각 (KST)")
        LocalDateTime startedAt,
        @Schema(description = "끝난 시각 (KST). 실행 중이면 null", nullable = true)
        LocalDateTime finishedAt,
        @Schema(description = "비교한 주문 항목 수 (진행 중이라 뺀 항목 제외, DEAD 표시·지갑에만 있는 보류 포함)")
        int checkedCount,
        @Schema(description = "어긋난 건 수 (HP_TASK_DEAD 표시 제외)")
        int mismatchCount,
        @Schema(description = "DEAD 작업이 있어 표시만 한 주문 항목 수")
        int deadCount,
        @Schema(description = "끝까지 비교하지 못한 이유. 완료면 null", nullable = true)
        String failureReason,
        @Schema(description = "어긋난 건과 HP_TASK_DEAD 표시 (남긴 순서). 실행 중이면 비어 있다")
        List<LectureHpReconciliationMismatchResponse> mismatches
) {

    public static LectureHpReconciliationRunResponse of(LectureHpReconciliationRun run,
                                                        List<LectureHpReconciliationMismatch> mismatches) {
        return new LectureHpReconciliationRunResponse(run.getId(), run.getTriggerType(), run.getTriggeredBy(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(), run.getCheckedCount(),
                run.getMismatchCount(), run.getDeadCount(), run.getFailureReason(),
                mismatches.stream().map(LectureHpReconciliationMismatchResponse::of).toList());
    }
}
