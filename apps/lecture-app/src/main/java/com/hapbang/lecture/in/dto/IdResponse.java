package com.hapbang.lecture.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "만든 리소스의 ID (강의 등록이면 강의 ID, 리뷰 작성이면 리뷰 ID)")
public record IdResponse(
        Long id
) {
}
