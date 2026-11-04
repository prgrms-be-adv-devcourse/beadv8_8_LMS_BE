package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 충전 주문 생성 요청 (#224). 단위·최소 금액(1,000 HP 이상, 1,000 HP 단위)은 서버가 CHARGE_AMOUNT_INVALID로 검사한다.
public record ChargeOrderRequest(
        @Schema(description = "충전 금액(HP = 원). 1,000 이상, 1,000 단위", example = "10000")
        @NotNull @Positive Long amount
) {
}
