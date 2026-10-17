package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceApplication;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 출연 모집 지원 요청. 메시지는 선택이다. 지원할 수 있는지(모집 중·마감 전·자기 모집글 아님·첫 지원)는 도메인에서 검사한다.
 */
public record ApplyAppearanceRecruitReqDto(
        @Schema(description = "지원 메시지 (선택, 500자 이하)", example = "같은 장르로 방송하고 있어 잘 맞을 것 같습니다.")
        @Size(max = AppearanceApplication.MESSAGE_MAX, message = "지원 메시지는 500자 이하여야 합니다.")
        String message
) {
}
