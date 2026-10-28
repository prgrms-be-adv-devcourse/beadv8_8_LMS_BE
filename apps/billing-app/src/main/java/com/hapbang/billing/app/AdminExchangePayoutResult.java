package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.domain.ExchangePayout;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;

// 관리자용 환전 송금 시도 (#304). 운영자 확인 대상 조회·환전별 시도 이력에 쓴다. 계좌번호는 담지 않는다.
// failureReason: 확정 실패면 Mock 은행 실패 코드, 결과 불명·운영자 확인이면 마지막 사유(예: NO_RESPONSE, AUTO_CHECK_EXPIRED,
// REJECTED_400:코드, AMOUNT_MISMATCH:금액). requestedBy: 관리자 수동 재송금이면 그 관리자, 자동 송금이면 null
public record AdminExchangePayoutResult(
        Long exchangeId,
        ExchangeStatus exchangeStatus,
        Long payoutId,
        int attemptNo,
        String payoutRequestId,
        ExchangePayoutStatus status,
        long amountKrw,
        String transactionId,
        String failureReason,
        Instant requestedAt,
        Long requestedBy,
        Instant completedAt,
        Instant failedAt,
        Instant lastCheckedAt,
        int resultCheckCount,
        Instant manualReviewAt,
        Long manualCheckedBy,
        Instant manualCheckedAt
) {

    static AdminExchangePayoutResult of(ExchangePayout payout) {
        return new AdminExchangePayoutResult(payout.getExchange().getId(), payout.getExchange().getStatus(),
                payout.getId(), payout.getAttemptNo(), payout.getPayoutRequestId(), payout.getStatus(),
                payout.getAmountKrw(), payout.getPayoutTransactionId(), payout.getFailureReason(),
                payout.getRequestedAt(), payout.getRequestedBy(), payout.getCompletedAt(), payout.getFailedAt(),
                payout.getLastCheckedAt(), payout.getResultCheckCount(), payout.getManualReviewAt(),
                payout.getManualCheckedBy(), payout.getManualCheckedAt());
    }
}
