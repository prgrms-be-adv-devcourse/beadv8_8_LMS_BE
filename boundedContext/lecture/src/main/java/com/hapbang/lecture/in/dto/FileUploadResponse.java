package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

public record FileUploadResponse(
        Long fileId,
        @Schema(description = "S3 Presigned PUT URL. 이 주소로 파일을 올린 뒤 complete를 호출한다")
        String uploadUrl,
        LocalDateTime expiresAt
) {
}
