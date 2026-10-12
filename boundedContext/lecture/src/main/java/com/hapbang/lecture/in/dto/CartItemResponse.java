package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CartItemResponse(
        Long lectureId,
        String title,
        long priceHp,
        @Schema(nullable = true)
        String thumbnailUrl,
        @Schema(description = "담은 뒤 판매 중지·삭제되면 false. 주문할 수 없다")
        boolean purchasable
) {
}
