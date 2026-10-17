package com.hapbang.billing.out;

import java.util.regex.Pattern;

// 송금 요청 (#288). 지급 요청 ID는 송금 건마다 고정된 UUID이며 HTTP Idempotency-Key로도 쓴다.
// amountKrw는 환전 예상 수령액(신청 금액 − 수수료, Exchange.payoutAmountKrw)이다. 신청 금액 전체가 아니다.
// 계좌번호는 테스트 계좌(ExchangeAccount 암호문을 복호화한 값)이며 toString·로그·예외 메시지에 남기지 않는다.
public record PayoutTransferRequest(String payoutRequestId, Long exchangeId, long amountKrw, String bankCode,
        String accountNumber, String accountHolderName) {

    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public PayoutTransferRequest {
        if (payoutRequestId == null || !UUID.matcher(payoutRequestId).matches()) {
            throw new IllegalArgumentException("지급 요청 ID는 소문자 UUID여야 합니다.");
        }
        if (exchangeId == null || amountKrw <= 0 || bankCode == null || accountNumber == null
                || accountHolderName == null) {
            throw new IllegalArgumentException("송금 요청 값이 비어 있거나 금액이 0 이하입니다. payoutRequestId=" + payoutRequestId);
        }
    }

    @Override
    public String toString() {
        return "PayoutTransferRequest[payoutRequestId=" + payoutRequestId + ", exchangeId=" + exchangeId
                + ", amountKrw=" + amountKrw + ", bankCode=" + bankCode + "]";
    }
}
