package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자의 판매 내역 한 줄 (주문 항목 단위)")
public record SaleResponse(
        Long orderItemId,
        Long lectureId,
        @Schema(description = "주문 시점 강의 제목")
        String lectureTitle,
        @Schema(description = "주문 시점 가격")
        long priceHp,
        OrderItemStatus status,
        LocalDateTime purchasedAt,
        @Schema(description = "지급 예정 시각 (구매일 + 31일 00:00 KST). 구매 확정되고 이의 제기가 없으면 이 시각 뒤 첫 지급 배치에서 지급한다")
        LocalDateTime payoutScheduledAt,
        @Schema(nullable = true)
        LocalDateTime paidOutAt,
        ObjectionStatus objectionStatus
) {

    public static SaleResponse of(OrderItem item) {
        return new SaleResponse(item.getId(), item.getLectureId(), item.getLectureTitleSnapshot(), item.getPriceHp(),
                item.getStatus(), item.getPurchasedAt(), item.getPayoutScheduledAt(), item.getPaidOutAt(),
                item.getObjectionStatus());
    }
}
