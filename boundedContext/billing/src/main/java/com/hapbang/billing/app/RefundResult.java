package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;

import com.hapbang.billing.domain.PointRefund;
import com.hapbang.billing.domain.PointRefundRequest;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.PointRefundStatus;

// 환불 요청 하나의 상태 결과다 (#260). 결제키·PG 오류 원문은 담지 않는다.
// requestedAmount = 충전 건별 금액 합계, completedAmount = PG 취소 성공 합계, returnedAmount = 반환(FAILED·CANCELED) 합계,
// pendingAmount = 아직 보류 중인 금액(PENDING·PROCESSING·UNKNOWN·MANUAL_REVIEW_REQUIRED).
public record RefundResult(
        Long refundRequestId,
        long requestedAmount,
        long completedAmount,
        long returnedAmount,
        long pendingAmount,
        PointRefundRequestStatus status,
        Instant requestedAt,
        Instant completedAt,
        Instant canceledAt,
        List<Item> items
) {

    public RefundResult {
        items = List.copyOf(items);
    }

    static RefundResult of(PointRefundRequest request, List<PointRefund> refunds) {
        long returned = 0;
        long pending = 0;
        for (PointRefund refund : refunds) {
            switch (refund.getStatus()) {
                case FAILED, CANCELED -> returned += refund.getRefundAmountHp();
                case PENDING, PROCESSING, UNKNOWN, MANUAL_REVIEW_REQUIRED -> pending += refund.getRefundAmountHp();
                case SUCCEEDED -> {
                }
            }
        }
        return new RefundResult(request.getId(), request.getRequestedAmountHp(), request.getCompletedAmountHp(),
                returned, pending, request.getStatus(), request.getRequestedAt(), request.getCompletedAt(),
                request.getCanceledAt(), refunds.stream().map(Item::from).toList());
    }

    // 충전 건(PG 결제 건) 하나의 취소. FIFO 순서(충전 완료 시각, 충전 건 ID)다.
    public record Item(Long refundId, Long chargeId, long amount, PointRefundStatus status, Instant completedAt,
            Instant failedAt) {

        static Item from(PointRefund refund) {
            return new Item(refund.getId(), refund.getChargeId(), refund.getRefundAmountHp(), refund.getStatus(),
                    refund.getCompletedAt(), refund.getFailedAt());
        }
    }
}
