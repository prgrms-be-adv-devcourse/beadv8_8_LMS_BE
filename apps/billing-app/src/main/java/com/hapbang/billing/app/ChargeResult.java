package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointChargeStatus;

// 충전 주문 하나의 상태 결과다 (#224). 결제키·내부 확인 사유는 담지 않는다(실패 사유는 FAILED일 때만 담는다).
public record ChargeResult(
        String orderId,
        long amount,
        PointChargeStatus status,
        Instant requestedAt,
        Instant completedAt,
        Instant failedAt,
        String failureReason
) {

    public static ChargeResult from(PointCharge charge) {
        return new ChargeResult(charge.getPgOrderId(), charge.getAmountHp(), charge.getStatus(),
                charge.getRequestedAt(), charge.getCompletedAt(), charge.getFailedAt(),
                charge.getStatus() == PointChargeStatus.FAILED ? charge.getFailureReason() : null);
    }
}
