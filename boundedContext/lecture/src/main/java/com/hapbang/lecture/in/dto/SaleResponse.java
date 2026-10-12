package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자의 판매 내역 한 줄 (주문 항목 단위)")
public record SaleResponse(
        Long orderItemId,
        Long lectureId,
        String lectureTitle,
        long priceHp,
        OrderItemStatus status,
        LocalDateTime purchasedAt,
        @Schema(description = "지급 예정 시각")
        LocalDateTime payoutScheduledAt,
        @Schema(nullable = true)
        LocalDateTime paidOutAt,
        ObjectionStatus objectionStatus
) {
}
