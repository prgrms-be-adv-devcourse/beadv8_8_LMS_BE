package com.hapbang.lecture.in.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record CartResponse(
        List<CartItemResponse> items,
        @Schema(description = "구매 가능한 항목의 가격 합")
        long purchasableTotalHp
) {
}
