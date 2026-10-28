package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 충전 HP 환불 요청 (#260). 단위·최소 금액(1,000 HP 이상, 1,000 HP 단위)은 서버가 REFUND_AMOUNT_INVALID로 검사한다.
// 회원 탈퇴 환불(1 HP 단위)은 회원 탈퇴가 구현되지 않아 이 API로 고를 수 없다.
public record RefundRequest(
        @Schema(description = "환불 금액(HP = 원). 1,000 이상, 1,000 단위", example = "5000")
        @NotNull @Positive Long amount
) {
}
