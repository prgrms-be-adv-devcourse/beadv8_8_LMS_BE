package com.hapbang.billing.in;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;

import com.hapbang.billing.app.ChargeResult;
import com.hapbang.billing.domain.PointChargeStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 충전 주문 응답 (#224). 결제키는 담지 않는다. orderName은 결제창(requestPayment)의 orderName으로 쓴다.
public record ChargeResponse(
        @Schema(description = "주문 ID. 결제창 orderId로 쓴다")
        String orderId,
        @Schema(description = "결제창 주문명", example = "HP 10,000 충전")
        String orderName,
        @Schema(description = "충전 금액(HP = 원). 결제창 amount로 쓴다")
        long amount,
        @Schema(description = "PENDING(결제 전), PROCESSING(승인 결과 확인 중), COMPLETED(충전됨), FAILED(결제 미승인)")
        PointChargeStatus status,
        Instant requestedAt,
        Instant completedAt,
        Instant failedAt,
        @Schema(description = "FAILED일 때 PG가 알려 준 실패 사유", nullable = true)
        String failureReason
) {

    static ChargeResponse from(ChargeResult result) {
        return new ChargeResponse(result.orderId(), orderName(result.amount()), result.amount(), result.status(),
                result.requestedAt(), result.completedAt(), result.failedAt(), result.failureReason());
    }

    private static String orderName(long amount) {
        return "HP " + NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + " 충전";
    }
}
