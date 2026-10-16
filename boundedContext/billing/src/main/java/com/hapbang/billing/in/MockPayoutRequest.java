package com.hapbang.billing.in;

import com.hapbang.billing.app.MockPayoutCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 가상 송금 요청 본문 (#288). 송금 금액은 환전 예상 수령액(신청 금액 − 수수료, KRW)이다.
// 계좌번호는 실제 계좌가 아닌 테스트 계좌이며 조회·실명 확인을 하지 않는다. toString·로그에 계좌번호를 남기지 않는다.
public record MockPayoutRequest(
        @NotNull @Pattern(regexp = MockPayoutRequest.UUID_PATTERN) String payoutRequestId,
        @NotNull @Positive Long exchangeId,
        @NotNull @Positive Long amountKrw,
        @NotNull @Pattern(regexp = "\\d{3}") String bankCode,
        @NotNull @Pattern(regexp = "\\d{8,20}") String accountNumber,
        @NotBlank @Size(max = 50) String accountHolderName
) {

    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    MockPayoutCommand toCommand() {
        return new MockPayoutCommand(payoutRequestId, exchangeId, amountKrw, bankCode, accountNumber,
                accountHolderName);
    }

    @Override
    public String toString() {
        return "MockPayoutRequest[payoutRequestId=" + payoutRequestId + ", exchangeId=" + exchangeId + ", amountKrw="
                + amountKrw + ", bankCode=" + bankCode + "]";
    }
}
