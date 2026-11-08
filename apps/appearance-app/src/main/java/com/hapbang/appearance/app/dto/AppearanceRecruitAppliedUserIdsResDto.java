package com.hapbang.appearance.app.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 요청한 회원 중 모집글에 지원한 적이 있는 회원 (내부 API, #586) */
public record AppearanceRecruitAppliedUserIdsResDto(
        @Schema(description = "요청한 회원 중 지원 이력이 있는 회원 ID (지원 상태 무관), 오름차순·중복 없음") List<Long> appliedUserIds
) {
}
