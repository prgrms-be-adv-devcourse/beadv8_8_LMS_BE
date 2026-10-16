package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.domain.MockPayoutTransfer;
import com.hapbang.billing.domain.MockPayoutTransferStatus;

// Mock 은행의 가상 송금 기록 (#288). 계좌 정보는 담지 않는다.
public record MockPayoutTransferView(String payoutRequestId, Long exchangeId, long amountKrw,
        MockPayoutTransferStatus status, String transactionId, String failureCode, Instant requestedAt,
        Instant completedAt) {

    static MockPayoutTransferView of(MockPayoutTransfer transfer) {
        return new MockPayoutTransferView(transfer.getPayoutRequestId(), transfer.getExchangeId(),
                transfer.getAmountKrw(), transfer.getStatus(), transfer.getTransactionId(), transfer.getFailureCode(),
                transfer.getRequestedAt(), transfer.getCompletedAt());
    }
}
