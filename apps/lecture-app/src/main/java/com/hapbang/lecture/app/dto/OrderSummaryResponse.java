package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderProgress;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderSummaryResponse(
        Long orderId,
        long totalAmountHp,
        LectureOrderStatus status,
        @Schema(nullable = true)
        OrderProgress progress,
        @Schema(description = "대표 강의 제목 (첫 항목)")
        String representativeTitle,
        int itemCount,
        LocalDateTime orderedAt
) {

    public static OrderSummaryResponse of(LectureOrder order) {
        return new OrderSummaryResponse(order.getId(), order.getTotalAmountHp(), order.getStatus(), order.progress(),
                order.getItems().getFirst().getLectureTitleSnapshot(), order.getItems().size(), order.getOrderedAt());
    }
}
