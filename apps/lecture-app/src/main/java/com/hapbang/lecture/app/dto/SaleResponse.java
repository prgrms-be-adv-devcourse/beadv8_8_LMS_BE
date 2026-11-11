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
        @Schema(description = "쿠폰 할인 합(판매자 부담 + 플랫폼 부담). 쿠폰을 쓰지 않았으면 0")
        long discountHp,
        @Schema(description = "할인 중 판매자가 부담한 몫. 지급액에서 빠진다")
        long sellerDiscountHp,
        @Schema(description = "할인 중 플랫폼이 부담한 몫. 지급액에서 빠지지 않는다")
        long platformDiscountHp,
        @Schema(description = "판매자 지급액 = 가격 − 판매자 부담 할인")
        long sellerPayoutHp,
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
                item.getDiscountHp(), item.getSellerDiscountHp(), item.getPlatformDiscountHp(), item.sellerPayoutHp(),
                item.getStatus(), item.getPurchasedAt(), item.getPayoutScheduledAt(), item.getPaidOutAt(),
                item.getObjectionStatus());
    }
}
