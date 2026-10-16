package com.hapbang.billing.in;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hapbang.billing.app.RefundResult;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.PointRefundStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 환불 요청 응답 (#260). 결제키·PG 오류 원문은 담지 않는다.
public record RefundResponse(
        Long refundRequestId,
        @Schema(description = "요청 금액(HP)")
        long requestedAmount,
        @Schema(description = "환불(PG 취소 성공) 금액")
        long completedAmount,
        @Schema(description = "환불하지 못해 충전 HP로 반환한 금액")
        long returnedAmount,
        @Schema(description = "아직 보류 중인 금액(결과 확인 중·운영자 확인)")
        long pendingAmount,
        @Schema(description = "REQUESTED, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, FAILED, MANUAL_REVIEW_REQUIRED, CANCELED")
        PointRefundRequestStatus status,
        Instant requestedAt,
        Instant completedAt,
        Instant canceledAt,
        @Schema(description = "결제 건별 취소(FIFO 순서)")
        List<Item> items
) {

    static RefundResponse from(RefundResult result) {
        return new RefundResponse(result.refundRequestId(), result.requestedAmount(), result.completedAmount(),
                result.returnedAmount(), result.pendingAmount(), result.status(), result.requestedAt(),
                result.completedAt(), result.canceledAt(), result.items().stream().map(Item::from).toList());
    }

    // 처리가 끝난 상태인지(200으로 응답할지) 확인한다.
    @JsonIgnore
    boolean closed() {
        return switch (status) {
            case COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELED -> true;
            case REQUESTED, PROCESSING, MANUAL_REVIEW_REQUIRED -> false;
        };
    }

    public record Item(
            Long refundId,
            @Schema(description = "결제 건 하나에서 취소할 금액")
            long amount,
            @Schema(description = "PENDING, PROCESSING, SUCCEEDED, FAILED, UNKNOWN, MANUAL_REVIEW_REQUIRED, CANCELED")
            PointRefundStatus status,
            Instant completedAt,
            Instant failedAt
    ) {

        static Item from(RefundResult.Item item) {
            return new Item(item.refundId(), item.amount(), item.status(), item.completedAt(), item.failedAt());
        }
    }
}
