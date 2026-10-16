package com.hapbang.billing.in;

import java.time.Instant;

import com.hapbang.billing.app.MockPayoutTransferView;
import com.hapbang.billing.domain.MockPayoutTransferStatus;

// 가상 송금 결과 (#288). 계좌 정보는 담지 않는다.
// status: SUCCEEDED(성공, transactionId 있음), FAILED(확정 실패, failureCode 있음), PROCESSING(접수됨·결과 전)
public record MockPayoutResponse(String payoutRequestId, Long exchangeId, long amountKrw,
        MockPayoutTransferStatus status, String transactionId, String failureCode, Instant requestedAt,
        Instant completedAt) {

    static MockPayoutResponse from(MockPayoutTransferView view) {
        return new MockPayoutResponse(view.payoutRequestId(), view.exchangeId(), view.amountKrw(), view.status(),
                view.transactionId(), view.failureCode(), view.requestedAt(), view.completedAt());
    }
}
