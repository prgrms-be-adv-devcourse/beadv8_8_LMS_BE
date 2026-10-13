package com.hapbang.lecture.in.dto;

import com.hapbang.lecture.domain.FileType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FileUploadRequest(
        @Schema(description = "썸네일 image/jpeg·png·webp 5MB, 영상 video/mp4 2GB, 자료 application/pdf 50MB까지")
        @NotNull FileType fileType,
        @Schema(description = "표시용 원본 파일명. S3 key에는 쓰지 않는다")
        @NotBlank @Size(max = 255) String originalFilename,
        @Schema(description = "MIME 타입 (예: video/mp4). 업로드할 때 같은 값을 Content-Type 헤더로 보낸다")
        @NotBlank String contentType,
        @Schema(description = "바이트. 업로드 완료 때 실제 크기와 같아야 한다")
        @NotNull @Positive Long fileSize
) {
}
