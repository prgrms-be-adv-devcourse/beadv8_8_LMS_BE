package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderProgress;
import com.hapbang.lecture.domain.ProcessingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderSummaryResponse(
        Long orderId,
        long totalAmountHp,
        LectureOrderStatus status,
        @Schema(nullable = true)
        OrderProgress progress,
        @Schema(nullable = true, description = "결제 단계일 때만 있다 (결제 처리 중·결제 취소 처리 중)")
        ProcessingStatus processingStatus,
        @Schema(description = "대표 강의 제목 (첫 항목)")
        String representativeTitle,
        int itemCount,
        LocalDateTime orderedAt
) {

    public static OrderSummaryResponse of(LectureOrder order, ProcessingStatus processingStatus) {
        return new OrderSummaryResponse(order.getId(), order.getTotalAmountHp(), order.getStatus(), order.progress(),
                processingStatus, order.getItems().getFirst().getLectureTitleSnapshot(), order.getItems().size(), order.getOrderedAt());
    }
}
