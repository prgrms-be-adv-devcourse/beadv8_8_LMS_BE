package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

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
}
