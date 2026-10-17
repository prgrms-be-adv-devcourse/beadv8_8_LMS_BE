package com.hapbang.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Mock 은행 쪽 가상 송금 기록 (#288). 지급 요청 ID(멱등키)별로 받은 요청 내용과 결과를 남겨, 같은 키 재요청·결과 조회에 같은 결과를
// 돌려준다. 우리 쪽 환전 송금 이력(ExchangePayout)이나 지갑·보류·원장과는 무관하다(실제 금전 이동 없음).
// 계좌번호 원문은 두지 않고 HMAC만 둔다.
@Entity
@Table(name = "mock_payout_transfer", schema = "billing",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mock_payout_transfer_payout_request_id", columnNames = {"payout_request_id"}),
                @UniqueConstraint(name = "uk_mock_payout_transfer_transaction_id", columnNames = {"transaction_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockPayoutTransfer extends BillingUpdatedEntity {

    @Column(name = "payout_request_id", nullable = false, length = 64)
    private String payoutRequestId;

    @Column(name = "exchange_id", nullable = false)
    private Long exchangeId;

    @Column(name = "amount_krw", nullable = false)
    private Long amountKrw;

    @Column(name = "bank_code", nullable = false, length = 3)
    private String bankCode;

    @Column(name = "account_number_hash", nullable = false, length = 64)
    private String accountNumberHash;

    @Column(name = "account_holder_name", nullable = false, length = 50)
    private String accountHolderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MockPayoutTransferStatus status;

    @Column(name = "transaction_id", nullable = true, length = 64)
    private String transactionId;

    @Column(name = "failure_code", nullable = true, length = 64)
    private String failureCode;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private Instant completedAt;

    // 접수한 요청(PROCESSING)
    public static MockPayoutTransfer received(String payoutRequestId, Long exchangeId, long amountKrw, String bankCode,
            String accountNumberHash, String accountHolderName, Instant requestedAt) {
        MockPayoutTransfer transfer = new MockPayoutTransfer();
        transfer.payoutRequestId = payoutRequestId;
        transfer.exchangeId = exchangeId;
        transfer.amountKrw = amountKrw;
        transfer.bankCode = bankCode;
        transfer.accountNumberHash = accountNumberHash;
        transfer.accountHolderName = accountHolderName;
        transfer.status = MockPayoutTransferStatus.PROCESSING;
        transfer.requestedAt = requestedAt;
        return transfer;
    }

    // 같은 지급 요청 ID로 온 요청이 처음 요청과 같은 내용인지(지급 요청 ID 외 모든 값)
    public boolean sameRequest(Long exchangeId, long amountKrw, String bankCode, String accountNumberHash,
            String accountHolderName) {
        return this.exchangeId.equals(exchangeId) && this.amountKrw == amountKrw && this.bankCode.equals(bankCode)
                && this.accountNumberHash.equals(accountNumberHash) && this.accountHolderName.equals(accountHolderName);
    }

    public void succeed(String transactionId, Instant completedAt) {
        requireProcessing();
        this.status = MockPayoutTransferStatus.SUCCEEDED;
        this.transactionId = transactionId;
        this.completedAt = completedAt;
    }

    public void fail(String failureCode, Instant completedAt) {
        requireProcessing();
        this.status = MockPayoutTransferStatus.FAILED;
        this.failureCode = failureCode;
        this.completedAt = completedAt;
    }

    private void requireProcessing() {
        if (status != MockPayoutTransferStatus.PROCESSING) {
            throw new IllegalStateException("이미 결과가 정해진 가상 송금입니다. payoutRequestId=" + payoutRequestId
                    + ", status=" + status);
        }
    }
}
