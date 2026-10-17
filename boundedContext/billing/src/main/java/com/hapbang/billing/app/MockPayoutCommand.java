package com.hapbang.billing.app;

// Mock 은행이 받은 가상 송금 요청 (#288). 계좌번호는 실제 계좌가 아닌 테스트 계좌이며 로그·toString에 남기지 않는다.
public record MockPayoutCommand(String payoutRequestId, Long exchangeId, long amountKrw, String bankCode,
        String accountNumber, String accountHolderName) {

    @Override
    public String toString() {
        return "MockPayoutCommand[payoutRequestId=" + payoutRequestId + ", exchangeId=" + exchangeId + ", amountKrw="
                + amountKrw + ", bankCode=" + bankCode + "]";
    }
}
