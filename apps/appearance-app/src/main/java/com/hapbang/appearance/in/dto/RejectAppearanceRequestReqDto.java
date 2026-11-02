package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceRequestRules;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 출연 요청 거절 요청. 사유는 선택 입력이다 */
public record RejectAppearanceRequestReqDto(
        @Schema(description = "거절 사유 (선택, 500자 이하)", example = "해당 일정에 다른 방송이 있습니다.")
        @Size(max = AppearanceRequestRules.REJECT_REASON_MAX, message = "거절 사유는 500자 이하여야 합니다.")
        String reason
) {
}
