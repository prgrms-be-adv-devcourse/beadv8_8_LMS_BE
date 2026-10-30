package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.ConfirmationType;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.domain.ProcessingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderItemResponse(
        Long orderItemId,
        Long lectureId,
        @Schema(description = "주문 시점 강의 제목")
        String lectureTitle,
        @Schema(description = "주문 시점 가격")
        long priceHp,
        OrderItemStatus status,
        @Schema(nullable = true, description = "결제 단계일 때만 있다. 주문의 처리 상태와 같다 (D69)")
        ProcessingStatus processingStatus,
        @Schema(nullable = true, description = "결제가 끝난 시각. 결제 단계에는 없다")
        LocalDateTime purchasedAt,
        @Schema(nullable = true)
        LocalDateTime confirmedAt,
        @Schema(nullable = true)
        ConfirmationType confirmationType,
        @Schema(nullable = true, description = "이 시각 전까지 구매 취소 가능 (구매일 + 8일 00:00 KST). 결제 단계에는 없다")
        LocalDateTime autoConfirmAt,
        @Schema(nullable = true,
                description = "이 시각 전까지 내용 다름 이의 제기 가능 (구매일 + 31일 00:00 KST). 결제 단계에는 없다")
        LocalDateTime payoutScheduledAt,
        @Schema(nullable = true)
        LocalDateTime canceledAt,
        @Schema(nullable = true)
        LocalDateTime returnedAt,
        ObjectionStatus objectionStatus
) {

    /** 처리 중이 아닌 항목 (구매 확정·구매 취소 응답 등) */
    public static OrderItemResponse of(OrderItem item) {
        return of(item, null);
    }

    public static OrderItemResponse of(OrderItem item, ProcessingStatus processingStatus) {
        return new OrderItemResponse(item.getId(), item.getLectureId(), item.getLectureTitleSnapshot(),
                item.getPriceHp(), item.getStatus(), processingStatus, item.getPurchasedAt(), item.getConfirmedAt(),
                item.getConfirmationType(), item.getAutoConfirmAt(), item.getPayoutScheduledAt(),
                item.getCanceledAt(), item.getReturnedAt(), item.getObjectionStatus());
    }
}
