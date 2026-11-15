package com.hapbang.coupon.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 복구 재개 요청 */
public record RecoveryResumeRequest(
        @Schema(description = "재개 근거 (1000자 이하). 유실 의심 접수를 어떻게 확인했는지 등 재개를 판단한 이유")
        @NotBlank @Size(max = 1000) String resumeBasis
) {
}
