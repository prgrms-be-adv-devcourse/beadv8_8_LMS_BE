package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;
import java.util.List;

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
}
