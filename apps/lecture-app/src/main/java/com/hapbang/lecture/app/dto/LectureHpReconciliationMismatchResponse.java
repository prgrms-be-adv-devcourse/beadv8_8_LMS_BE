package com.hapbang.lecture.app.dto;

import com.hapbang.lecture.domain.LectureHpReconciliationMismatch;
import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 HP 대사에서 어긋난 건 하나 (관리자, D74). 고치지 않고 남기기만 한다")
public record LectureHpReconciliationMismatchResponse(
        Long id,
        @Schema(description = "주문 항목 ID (지갑 참조 ID)")
        Long orderItemId,
        @Schema(description = "주문 ID. 지갑에만 있는 보류(WALLET_ONLY_HOLD)는 강의에 항목이 없어 null", nullable = true)
        Long orderId,
        @Schema(description = "어긋난 이유. HP_TASK_DEAD는 DEAD 작업이 있어 비교하지 않았다는 표시")
        LectureHpReconciliationRule rule,
        @Schema(description = "비교할 때의 항목 상태. 지갑에만 있는 보류는 null", nullable = true)
        OrderItemStatus itemStatus,
        @Schema(description = "강의 쪽 상태 요약: 항목·주문 ID, 상태, 가격, 구매자, 판매자")
        String lectureState,
        @Schema(description = "지갑 쪽 상태 요약: 보류 금액(합계), 무효화 기록, 지급 건")
        String walletState
) {

    public static LectureHpReconciliationMismatchResponse of(LectureHpReconciliationMismatch mismatch) {
        return new LectureHpReconciliationMismatchResponse(mismatch.getId(), mismatch.getOrderItemId(),
                mismatch.getOrderId(), mismatch.getRule(), mismatch.getItemStatus(), mismatch.getLectureState(),
                mismatch.getWalletState());
    }
}
