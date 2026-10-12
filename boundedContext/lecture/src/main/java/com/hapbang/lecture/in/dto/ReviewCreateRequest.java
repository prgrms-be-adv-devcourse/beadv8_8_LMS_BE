package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewCreateRequest(
        @Schema(description = "구매 확정(CONFIRMED)·지급(PAID_OUT)된 본인 주문 항목")
        @NotNull Long orderItemId,
        @NotNull @Min(1) @Max(5) Integer rating,
        String content
) {
}
