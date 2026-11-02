package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 충전 HP 환불 요청 (#260). 금액은 1 HP 이상·1 HP 단위다(#453). 0 이하는 @Positive가 400 INVALID_REQUEST로 막고,
// 서버도 REFUND_AMOUNT_TOO_SMALL로 한 번 더 검사한다.
public record RefundRequest(
        @Schema(description = "환불 금액(HP = 원). 1 이상, 1 단위", example = "5300")
        @NotNull @Positive Long amount
) {
}
