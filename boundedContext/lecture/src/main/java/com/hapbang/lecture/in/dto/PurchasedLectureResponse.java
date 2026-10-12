package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.OrderItemStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "시청 가능한 강의 (항목이 PURCHASED·CONFIRMED·PAID_OUT)")
public record PurchasedLectureResponse(
        Long lectureId,
        Long orderId,
        Long orderItemId,
        String title,
        @Schema(nullable = true)
        String thumbnailUrl,
        @Schema(description = "PURCHASED면 영상·자료를 보기 전에 재생 동의가 필요하다")
        OrderItemStatus itemStatus,
        @Schema(description = "판매자가 삭제한 강의. 기존 구매자는 계속 볼 수 있다")
        boolean lectureDeleted,
        LocalDateTime purchasedAt
) {
}
