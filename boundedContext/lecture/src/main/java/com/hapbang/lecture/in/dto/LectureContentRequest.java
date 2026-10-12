package com.hapbang.lecture.in.dto;

import com.hapbang.lecture.domain.ContentType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "강의 콘텐츠 한 개. 순서는 목록 순서로 정한다(1부터).")
public record LectureContentRequest(
        @Schema(description = "업로드 완료(UPLOADED)된 본인 파일 ID")
        @NotNull Long fileId,
        @NotBlank String title,
        @NotNull ContentType contentType
) {
}
