package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 HP 작업 (관리자, D57). 원래 작업 상태·시도 횟수·오류 코드를 그대로 보여 준다")
public record LectureHpTaskResponse(
        Long id,
        LectureHpTaskStatus status,
        @Schema(description = "COMPLETED일 때만 있다", nullable = true)
        LectureHpTaskOutcome outcome,
        LectureHpTaskType taskType,
        LectureHpTaskPurpose purpose,
        @Schema(description = "지갑 요청 ID(멱등 키). 관리자 재시도도 같은 값으로 보내므로 HP가 두 번 움직이지 않는다")
        String requestId,
        Long orderId,
        Long orderItemId,
        @Schema(description = "구매자")
        Long payerUserId,
        @Schema(description = "지급받는 판매자. 지급 작업만", nullable = true)
        Long receiverUserId,
        @Schema(description = "항목 가격")
        long amountHp,
        @Schema(description = "현재 자동 재시도 주기의 선점 횟수. 관리자 재시도 때 0이 된다")
        int attemptCount,
        @Schema(description = "관리자 재시도를 포함한 누적 선점 횟수")
        int totalAttemptCount,
        int manualRetryCount,
        LocalDateTime nextAttemptAt,
        @Schema(description = "마지막 실패 코드. 지갑 실패 코드(예: WALLET_NOT_FOUND) 또는 실행기 결과"
                + "(RETRYABLE, UNKNOWN_RESULT, AMOUNT_MISMATCH, LEASE_EXPIRED, RESULT_HANDLER_MISSING)", nullable = true)
        String lastErrorCode,
        LocalDateTime lastFailedAt,
        Long lastRetriedByUserId,
        LocalDateTime lastRetriedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {

    public static LectureHpTaskResponse of(LectureHpTask task) {
        return new LectureHpTaskResponse(task.getId(), task.getStatus(), task.getOutcome(), task.getTaskType(),
                task.getPurpose(), task.getRequestId(), task.getOrderId(), task.getOrderItemId(),
                task.getPayerUserId(), task.getReceiverUserId(), task.getAmountHp(), task.getAttemptCount(),
                task.getTotalAttemptCount(), task.getManualRetryCount(), task.getNextAttemptAt(),
                task.getLastErrorCode(), task.getLastFailedAt(), task.getLastRetriedByUserId(),
                task.getLastRetriedAt(), task.getCompletedAt(), task.getCreatedAt());
    }
}
