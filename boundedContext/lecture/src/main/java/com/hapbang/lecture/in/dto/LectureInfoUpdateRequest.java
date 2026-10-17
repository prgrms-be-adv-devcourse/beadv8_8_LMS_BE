package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "제목·소개·가격·썸네일 전체를 보낸다. 재검수 대상이 아니다.")
public record LectureInfoUpdateRequest(
        @NotBlank String title,
        String description,
        @Schema(description = "1,000 HP 이상, 1,000 HP 단위")
        @NotNull Long priceHp,
        @Schema(description = "null이면 썸네일을 지운다. 유지하려면 상세의 thumbnailFileId를 그대로 보낸다", nullable = true)
        Long thumbnailFileId
) {
}
