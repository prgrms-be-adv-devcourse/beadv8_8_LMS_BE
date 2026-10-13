package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.hapbang.global.storage.PresignedUrl;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContentAccessResponse(
        @Schema(description = "S3 Presigned GET URL. 서명이 들어 있으므로 저장하거나 공유하지 않는다")
        String url,
        @Schema(description = "URL 만료 시각(서버 시간대). 만료 뒤에는 다시 발급받는다")
        LocalDateTime expiresAt
) {

    public static ContentAccessResponse from(PresignedUrl presignedUrl) {
        return new ContentAccessResponse(presignedUrl.url(),
                LocalDateTime.ofInstant(presignedUrl.expiresAt(), ZoneId.systemDefault()));
    }
}
