package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewCreateRequest(
        @Schema(description = "구매 확정(CONFIRMED)·지급(PAID_OUT)된 본인 주문 항목")
        @NotNull Long orderItemId,
        @Schema(description = "평점 (1~5)")
        @NotNull @Min(1) @Max(5) Integer rating,
        @Schema(description = "본문 (선택, 1,000자 이하). 비었거나 공백만 있으면 저장하지 않는다", nullable = true)
        @Size(max = 1000) String content
) {
}
