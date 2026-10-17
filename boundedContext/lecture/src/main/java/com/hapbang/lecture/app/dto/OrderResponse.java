package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderProgress;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        Long orderId,
        long totalAmountHp,
        LectureOrderStatus status,
        @Schema(nullable = true)
        OrderProgress progress,
        LocalDateTime orderedAt,
        @Schema(nullable = true)
        LocalDateTime canceledAt,
        List<OrderItemResponse> items
) {

    public static OrderResponse of(LectureOrder order) {
        return new OrderResponse(order.getId(), order.getTotalAmountHp(), order.getStatus(), order.progress(),
                order.getOrderedAt(), order.getCanceledAt(),
                order.getItems().stream().map(OrderItemResponse::of).toList());
    }
}
