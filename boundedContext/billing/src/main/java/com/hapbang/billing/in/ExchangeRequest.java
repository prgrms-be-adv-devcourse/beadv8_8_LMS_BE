package com.hapbang.billing.in;

import com.hapbang.billing.app.ExchangeCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 수익 HP 환전 신청 (#285). 단위·최소 금액(1,000 HP 이상, 1,000 HP 단위)은 서버가 EXCHANGE_AMOUNT_INVALID로 검사한다.
// 계좌는 신청마다 입력하는 테스트 계좌다. 형식만 검사하고 실제 계좌 존재·예금주 일치는 확인하지 않는다.
public record ExchangeRequest(
        @Schema(description = "환전 신청 금액(수익 HP). 1,000 이상, 1,000 단위", example = "10000")
        @NotNull @Positive Long amount,
        @Schema(description = "은행 코드(숫자 3자리)", example = "004")
        @NotNull @Pattern(regexp = "\\d{3}") String bankCode,
        @Schema(description = "계좌번호(숫자만 8~20자리, 하이픈 없이). 저장 시 암호화하고 응답에는 마스킹한다", example = "12345678901234")
        @NotNull @Pattern(regexp = "\\d{8,20}") String accountNumber,
        @Schema(description = "예금주(앞뒤 공백 제외 1~50자)", example = "홍길동")
        @NotBlank @Size(max = 50) String accountHolderName
) {

    ExchangeCommand toCommand() {
        return new ExchangeCommand(amount, bankCode, accountNumber, accountHolderName);
    }

    // 계좌번호·예금주가 로그에 남지 않게 출력하지 않는다.
    @Override
    public String toString() {
        return "ExchangeRequest[amount=" + amount + ", bankCode=" + bankCode + "]";
    }
}
