package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 평점과 본문을 모두 바꾼다. 본문을 빼면 본문 없는 리뷰가 된다 */
public record ReviewUpdateRequest(
        @Schema(description = "평점 (1~5)")
        @NotNull @Min(1) @Max(5) Integer rating,
        @Schema(description = "본문 (선택, 1,000자 이하). 비었거나 공백만 있으면 저장하지 않는다", nullable = true)
        @Size(max = 1000) String content
) {
}
