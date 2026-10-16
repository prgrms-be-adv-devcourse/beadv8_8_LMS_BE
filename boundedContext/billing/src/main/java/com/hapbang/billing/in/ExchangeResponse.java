package com.hapbang.billing.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.hapbang.billing.app.ExchangeResult;
import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.ExchangeStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 환전 신청 응답 (#285). 계좌번호는 끝 4자리만 보이게 마스킹한다. 실제 송금일은 담지 않는다(잠정 정산 예정일만).
public record ExchangeResponse(
        Long exchangeId,
        @Schema(description = "신청 금액(수익 HP, 보류한 금액)")
        long requestedAmount,
        @Schema(description = "수수료율(0.1 = 10%)")
        BigDecimal feeRate,
        @Schema(description = "수수료(HP). 신청 금액 × 10%, 원 미만 버림. 신청 시 확정")
        long feeAmount,
        @Schema(description = "예상 수령액(원) = 신청 금액 − 수수료")
        long expectedPayoutAmount,
        @Schema(description = "REQUESTED(관리자 승인 전, 취소 가능), CANCELED(회원 취소, 보류 HP 반환), "
                + "PROCESSING·COMPLETED·FAILED·UNKNOWN(송금 단계, 후속 기능)")
        ExchangeStatus status,
        @Schema(description = "잠정 정산 예정일(KST). 신청일이 1~9일이면 당월 10일, 10일 이후면 다음 달 10일이고 토·일요일이면 "
                + "다음 월요일이다. 공휴일을 반영하지 않은 값이라 실제 송금일이 아니며, 실제 송금일은 정산 처리 때 공휴일·승인 여부를 "
                + "반영해 정해진다")
        LocalDate settlementRoundDate,
        Instant requestedAt,
        Instant canceledAt,
        Instant completedAt,
        Instant failedAt,
        @Schema(description = "신청 당시 입력한 계좌(이후 바뀌지 않는다)")
        Account account
) {

    static ExchangeResponse from(ExchangeResult result) {
        ExchangeResult.Account account = result.account();
        return new ExchangeResponse(result.exchangeId(), result.requestedAmountHp(), result.feeRate(),
                result.feeAmountHp(), result.payoutAmountKrw(), result.status(), result.settlementRoundDate(),
                result.requestedAt(), result.canceledAt(), result.completedAt(), result.failedAt(),
                new Account(account.bankCode(), account.accountNumberMasked(), account.accountHolderName(),
                        account.verificationStatus()));
    }

    public record Account(
            String bankCode,
            @Schema(description = "마스킹한 계좌번호(끝 4자리만 표시)", example = "**********1234")
            String accountNumberMasked,
            String accountHolderName,
            @Schema(description = "PENDING(확인하지 않은 테스트 계좌). 실제 계좌 확인을 하지 않으므로 VERIFIED가 되지 않는다")
            AccountVerificationStatus verificationStatus
    ) {
    }
}
