package com.hapbang.coupon.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매자 쿠폰 거절 요청 */
public record CouponRejectRequest(
        @Schema(description = "거절 사유 (500자 이하)")
        @NotBlank @Size(max = 500) String reason
) {
}
