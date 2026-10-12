package com.hapbang.lecture.in.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record LectureRegisterRequest(
        @NotBlank String title,
        String description,
        @Schema(description = "1,000 HP 이상, 1,000 HP 단위")
        @NotNull Long priceHp,
        @Schema(description = "업로드 완료된 썸네일 파일 ID", nullable = true)
        Long thumbnailFileId,
        @Schema(description = "1개 이상")
        @NotEmpty List<@Valid @NotNull LectureContentRequest> contents
) {
}
