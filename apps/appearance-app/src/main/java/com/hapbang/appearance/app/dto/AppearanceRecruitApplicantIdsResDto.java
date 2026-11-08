package com.hapbang.appearance.app.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** 모집글에 지원한 적이 있는 회원 ID 한 페이지 (내부 API, 회원 ID 오름차순 키셋, #586) */
public record AppearanceRecruitApplicantIdsResDto(
        @Schema(description = "지원 이력이 있는 회원 ID (지원 상태 무관), 오름차순") List<Long> userIds,
        @Schema(description = "다음 페이지 요청의 afterUserId. 마지막 페이지면 응답에서 빠진다",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL) Long nextAfterUserId
) {
}
