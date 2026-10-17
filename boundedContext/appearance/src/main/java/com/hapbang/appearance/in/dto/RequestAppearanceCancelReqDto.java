package com.hapbang.appearance.in.dto;

import com.hapbang.appearance.domain.AppearanceCancelRequest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 출연 취소 요청. 사유는 필수다 (없으면 도메인이 {@code INVALID_REQUEST}로 막는다).
 */
public record RequestAppearanceCancelReqDto(
        @Schema(description = "취소 사유 (필수, 500자 이하)", example = "갑자기 일정이 생겨 방송에 참여하기 어렵습니다.")
        @Size(max = AppearanceCancelRequest.REASON_MAX, message = "취소 사유는 500자 이하여야 합니다.")
        String reason
) {
}
