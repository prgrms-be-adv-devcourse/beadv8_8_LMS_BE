package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderProcessingStatuses;
import com.hapbang.lecture.domain.OrderProgress;
import com.hapbang.lecture.domain.ProcessingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        Long orderId,
        long totalAmountHp,
        LectureOrderStatus status,
        @Schema(nullable = true)
        OrderProgress progress,
        @Schema(nullable = true, description = "결제 단계일 때만 있다 (결제 처리 중·결제 취소 처리 중)")
        ProcessingStatus processingStatus,
        @Schema(description = "주문을 시도한 시각")
        LocalDateTime orderedAt,
        @Schema(nullable = true, description = "모든 보류가 성공해 결제가 끝난 시각")
        LocalDateTime paidAt,
        @Schema(nullable = true)
        LocalDateTime canceledAt,
        List<OrderItemResponse> items
) {

    /**
     * @param processing 주문·항목의 처리 상태 (D69 (2), D70 (7))
     */
    public static OrderResponse of(LectureOrder order, OrderProcessingStatuses processing) {
        return new OrderResponse(order.getId(), order.getTotalAmountHp(), order.getStatus(), order.progress(),
                processing.order(), order.getOrderedAt(), order.getPaidAt(), order.getCanceledAt(),
                order.getItems().stream().map(item -> OrderItemResponse.of(item, processing.item(item.getId())))
                        .toList());
    }
}
