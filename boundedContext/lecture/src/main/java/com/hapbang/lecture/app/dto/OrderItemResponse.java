package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.ConfirmationType;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderItemResponse(
        Long orderItemId,
        Long lectureId,
        @Schema(description = "주문 시점 강의 제목")
        String lectureTitle,
        @Schema(description = "주문 시점 가격")
        long priceHp,
        OrderItemStatus status,
        LocalDateTime purchasedAt,
        @Schema(nullable = true)
        LocalDateTime confirmedAt,
        @Schema(nullable = true)
        ConfirmationType confirmationType,
        @Schema(description = "이 시각 전까지 구매 취소 가능 (구매일 + 8일 00:00 KST)")
        LocalDateTime autoConfirmAt,
        @Schema(description = "이 시각 전까지 내용 다름 이의 제기 가능 (구매일 + 31일 00:00 KST)")
        LocalDateTime payoutScheduledAt,
        @Schema(nullable = true)
        LocalDateTime canceledAt,
        @Schema(nullable = true)
        LocalDateTime returnedAt,
        ObjectionStatus objectionStatus
) {

    public static OrderItemResponse of(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getLectureId(), item.getLectureTitleSnapshot(),
                item.getPriceHp(), item.getStatus(), item.getPurchasedAt(), item.getConfirmedAt(),
                item.getConfirmationType(), item.getAutoConfirmAt(), item.getPayoutScheduledAt(),
                item.getCanceledAt(), item.getReturnedAt(), item.getObjectionStatus());
    }
}
