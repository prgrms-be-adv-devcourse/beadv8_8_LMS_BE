package com.hapbang.coupon.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DLT 영구 거절 요청 */
public record CouponDltRejectRequest(
        @Schema(description = "거절 사유 (500자 이하). 발급 요청 결과의 거절 사유로도 남는다")
        @NotBlank @Size(max = 500) String reason
) {
}
