package com.hapbang.lecture.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record FileUploadResponse(
        @Schema(description = "등록한 파일 ID. 본문은 서버로 올린다(#107)")
        Long fileId
) {
}
