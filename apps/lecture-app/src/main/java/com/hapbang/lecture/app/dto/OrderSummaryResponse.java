package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderProgress;
import com.hapbang.lecture.domain.ProcessingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderSummaryResponse(
        Long orderId,
        @Schema(description = "주문 시점 가격의 합(할인 전)")
        long totalAmountHp,
        @Schema(description = "항목 쿠폰 할인의 합. 쿠폰을 쓰지 않았으면 0")
        long discountHp,
        @Schema(description = "구매자가 낸 HP의 합 = 가격 합 − 할인 합. 주문 내역 카드는 이 값을 결제액으로 보인다")
        long buyerAmountHp,
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
        // 항목은 목록 조회가 이미 묶어 읽은 것이라 합을 구해도 쿼리가 늘지 않는다(주문 상세와 같은 계산)
        return new OrderSummaryResponse(order.getId(), order.getTotalAmountHp(),
                order.getItems().stream().mapToLong(OrderItem::getDiscountHp).sum(),
                order.getItems().stream().mapToLong(OrderItem::buyerAmountHp).sum(), order.getStatus(), order.progress(),
                processingStatus, order.getItems().getFirst().getLectureTitleSnapshot(), order.getItems().size(), order.getOrderedAt());
    }
}
