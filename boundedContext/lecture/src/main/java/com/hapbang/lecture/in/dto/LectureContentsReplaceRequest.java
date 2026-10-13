package com.hapbang.lecture.in.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "콘텐츠 목록 전체. 추가·수정·삭제·순서 변경을 모두 이 목록으로 표현한다.")
public record LectureContentsReplaceRequest(
        @NotEmpty List<@Valid @NotNull LectureContentRequest> contents
) {
}
