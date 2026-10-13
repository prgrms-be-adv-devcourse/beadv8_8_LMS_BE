package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.hapbang.global.storage.PresignedUrl;

import io.swagger.v3.oas.annotations.media.Schema;

public record FileUploadResponse(
        Long fileId,
        @Schema(description = "S3 Presigned PUT URL. 요청한 contentType과 같은 Content-Type 헤더로 이 주소에 파일을 올린 뒤 complete를 호출한다")
        String uploadUrl,
        @Schema(description = "업로드 URL 만료 시각. 이 시각 전에 업로드를 시작해야 한다")
        LocalDateTime expiresAt
) {

    public static FileUploadResponse of(Long fileId, PresignedUrl uploadUrl) {
        return new FileUploadResponse(fileId, uploadUrl.url(),
                LocalDateTime.ofInstant(uploadUrl.expiresAt(), ZoneId.systemDefault()));
    }
}
