package com.hapbang.lecture.in.dto;

import com.hapbang.lecture.domain.ContentType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 콘텐츠. 파일 URL은 /access로 따로 발급받는다.")
public record LectureContentResponse(
        Long contentId,
        String title,
        int sequence,
        ContentType contentType
) {
}
