package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;

import io.swagger.v3.oas.annotations.media.Schema;

/** 관리자가 보는 HP 작업 한 건 (#397) */
public record HpTaskAdminResDto(
        Long id,
        @Schema(description = "작업 종류: RELEASE 반환 / PENALTY 위약금 정산 / PAYOUT 지급") HpTaskType taskType,
        @Schema(description = "대상 종류: APPEARANCE_REQUEST 출연 요청서 / APPEARANCE_RECRUIT 출연 모집글") HpTaskRefType refType,
        @Schema(description = "대상 ID (요청서·모집글 ID)") Long refId,
        @Schema(description = "관련 출연 ID. 위약금 정산·지급, 출연 단위 반환에서만 있다") Long appearanceId,
        @Schema(description = "보류한 회원 (호스트). 반환받는 회원이자 지급·위약금을 내는 회원") Long memberId,
        @Schema(description = "받는 회원 (게스트). 위약금 정산·지급에서만 있다") Long receiverId,
        @Schema(description = "이 작업이 옮기는 HP 전체. 위약금 정산이면 정산 기준액(출연료)") long amount,
        @Schema(description = "그중 받는 회원에게 주는 HP. 위약금 정산이면 위약금, 지급이면 amount와 같다") Long payoutAmount,
        @Schema(description = "작업 사유 (거절·취소·만료·확인 등)") HpTaskReason reason,
        @Schema(description = "Billing 요청 ID로 쓰는 멱등 키. 다시 시도해도 같은 값을 쓴다") String idempotencyKey,
        @Schema(description = "PENDING 대기·재시도 중 / DONE 완료 / DEAD 격리") HpTaskStatus status,
        @Schema(description = "시도 횟수 (관리자가 다시 시도하면 0부터 다시 센다)") int attemptCount,
        @Schema(description = "다음에 실행할 수 있는 시각 (KST)") LocalDateTime nextAttemptAt,
        @Schema(description = "마지막 실패 내용. Billing 거절이면 {오류 코드}: {메시지}") String lastError,
        @Schema(description = "처리가 끝난 시각 (KST). DEAD면 격리된 시각") LocalDateTime completedAt,
        @Schema(description = "작업을 만든 시각 (KST)") LocalDateTime createdAt,
        @Schema(description = "관리자가 다시 시도한 횟수") int manualRetryCount,
        @Schema(description = "마지막으로 다시 시도한 관리자 회원 ID") Long lastManualRetryBy,
        @Schema(description = "마지막으로 다시 시도한 시각 (KST)") LocalDateTime lastManualRetryAt
) {

    public static HpTaskAdminResDto from(HpTask task) {
        return new HpTaskAdminResDto(task.getId(), task.getTaskType(), task.getRefType(), task.getRefId(),
                task.getAppearanceId(), task.getMemberId(), task.getReceiverId(), task.getAmount(),
                task.getPayoutAmount(), task.getReason(), task.getIdempotencyKey(), task.getStatus(),
                task.getAttemptCount(), task.getNextAttemptAt(), task.getLastError(), task.getCompletedAt(),
                task.getCreatedAt(), task.getManualRetryCount(), task.getLastManualRetryBy(),
                task.getLastManualRetryAt());
    }
}
