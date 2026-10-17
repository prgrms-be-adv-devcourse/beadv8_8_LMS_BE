package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceCancelRequest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 출연 취소 요청 거절. 거절 사유는 선택이고, 요청 본문 없이 보내도 된다.
 */
public record RejectAppearanceCancelReqDto(
        @Schema(description = "거절 사유 (선택, 500자 이하)", example = "이미 홍보가 진행되어 일정 변경이 어렵습니다.")
        @Size(max = AppearanceCancelRequest.REASON_MAX, message = "거절 사유는 500자 이하여야 합니다.")
        String reason
) {
}
