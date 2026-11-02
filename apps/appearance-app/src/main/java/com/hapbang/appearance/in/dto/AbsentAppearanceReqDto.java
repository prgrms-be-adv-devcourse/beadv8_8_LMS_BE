package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceCancelReasonRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 출연 불참 응답 (호스트, #282). 요청 본문 없이 보내도 된다 (사유 없음).
 */
public record AbsentAppearanceReqDto(
        @Schema(description = "불참 사유 (선택, 500자 이하)", example = "방송 시작 시각까지 연락이 되지 않았습니다.")
        @Size(max = AppearanceCancelReasonRules.REASON_MAX, message = "불참 사유는 500자 이하여야 합니다.")
        String reason
) {
}
