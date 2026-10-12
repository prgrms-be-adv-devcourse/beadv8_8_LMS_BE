package com.hapbang.lecture.in.dto;

import com.hapbang.lecture.domain.FileType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FileUploadRequest(
        @NotNull FileType fileType,
        @NotBlank String originalFilename,
        @Schema(description = "MIME 타입 (예: video/mp4)")
        @NotBlank String contentType,
        @Schema(description = "바이트")
        @Positive long fileSize
) {
}
