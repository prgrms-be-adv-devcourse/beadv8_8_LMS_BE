package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.appearance.domain.HpReconciliationMismatch;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.domain.HpReconciliationTrigger;

import io.swagger.v3.oas.annotations.media.Schema;

/** 서비스 간 HP 대사 실행 1회와 어긋난 건 (#581) */
public record HpReconciliationRunResDto(
        Long id,
        @Schema(description = "SCHEDULED 매일 배치 / MANUAL 관리자 실행") HpReconciliationTrigger triggerType,
        @Schema(description = "수동 실행한 관리자 회원 ID. 배치면 null") Long triggeredBy,
        @Schema(description = "RUNNING 실행 중 / COMPLETED 끝까지 비교 / INCOMPLETE 지갑 조회 실패 등으로 중단(다음 실행에서 다시 봄)")
        HpReconciliationStatus status,
        @Schema(description = "시작 시각 (KST)") LocalDateTime startedAt,
        @Schema(description = "끝난 시각 (KST). 실행 중이면 null") LocalDateTime finishedAt,
        @Schema(description = "비교한 요청서·모집글 수 (진행 중이라 뺀 건 제외)") int checkedCount,
        @Schema(description = "어긋난 건 수 (HP_TASK_DEAD 표시 제외)") int mismatchCount,
        @Schema(description = "격리된 HP 작업이 있어 표시만 한 요청서·모집글 수") int deadCount,
        @Schema(description = "끝까지 비교하지 못한 이유. 완료면 null") String failureReason,
        @Schema(description = "어긋난 건과 HP_TASK_DEAD 표시 (남긴 순서)") List<HpReconciliationMismatchResDto> mismatches
) {

    public static HpReconciliationRunResDto from(HpReconciliationRun run, List<HpReconciliationMismatch> mismatches) {
        return new HpReconciliationRunResDto(run.getId(), run.getTriggerType(), run.getTriggeredBy(), run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(), run.getCheckedCount(), run.getMismatchCount(),
                run.getDeadCount(), run.getFailureReason(),
                mismatches.stream().map(HpReconciliationMismatchResDto::from).toList());
    }
}
