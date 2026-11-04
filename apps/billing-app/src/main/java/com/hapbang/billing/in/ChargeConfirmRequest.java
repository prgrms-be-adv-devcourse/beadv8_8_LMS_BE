package com.hapbang.billing.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 결제 승인 요청 (#224). TossPayments 결제창 성공 리다이렉트(successUrl)의 쿼리 paymentKey·orderId·amount를 그대로 보낸다.
// amount는 서버에 저장한 주문 금액과 대조할 뿐이며, 충전 금액은 서버 주문 금액이다.
public record ChargeConfirmRequest(
        @Schema(description = "TossPayments 결제키(최대 200자)")
        @NotBlank @Size(max = 200) String paymentKey,
        @Schema(description = "충전 주문 생성 때 받은 주문 ID", example = "HPCHG-0f8fad5bd9cb469fa16570867728950e")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String orderId,
        @Schema(description = "결제 금액(원)", example = "10000")
        @NotNull @Positive Long amount
) {

    // 결제키가 로그에 찍히지 않게 한다
    @Override
    public String toString() {
        return "ChargeConfirmRequest[orderId=" + orderId + ", amount=" + amount + "]";
    }
}
