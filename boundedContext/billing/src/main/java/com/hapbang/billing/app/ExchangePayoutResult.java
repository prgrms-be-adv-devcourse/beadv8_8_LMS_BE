package com.hapbang.billing.app;

import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangePayout;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;

// 환전 송금 실행 결과 (#304). 상태는 실행이 끝난 시점의 DB 값이다(결과 반영이 실패했으면 반영 전 상태 그대로). 송금 시도가 없으면
// 시도 필드는 null이다. 계좌번호는 담지 않는다.
public record ExchangePayoutResult(
        Long exchangeId,
        ExchangeStatus exchangeStatus,
        Integer attemptNo,
        String payoutRequestId,
        ExchangePayoutStatus payoutStatus,
        String transactionId,
        String failureReason,
        ExchangePayoutAction action
) {

    static ExchangePayoutResult of(Exchange exchange, ExchangePayout payout, ExchangePayoutAction action) {
        if (payout == null) {
            return new ExchangePayoutResult(exchange.getId(), exchange.getStatus(), null, null, null, null, null,
                    action);
        }
        return new ExchangePayoutResult(exchange.getId(), exchange.getStatus(), payout.getAttemptNo(),
                payout.getPayoutRequestId(), payout.getStatus(), payout.getPayoutTransactionId(),
                payout.getFailureReason(), action);
    }
}
