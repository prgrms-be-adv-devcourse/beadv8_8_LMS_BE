package com.hapbang.billing.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.ExchangeStatus;

// 환전 신청 결과 (#285). 계좌번호는 마스킹한 값만 담는다(암호문·원문 없음).
// settlementRoundDate는 잠정 정산 예정일(매월 10일, 주말이면 다음 월요일)이며 공휴일을 반영한 실제 송금일이 아니다.
public record ExchangeResult(
        Long exchangeId,
        long requestedAmountHp,
        BigDecimal feeRate,
        long feeAmountHp,
        long payoutAmountKrw,
        ExchangeStatus status,
        LocalDate settlementRoundDate,
        Instant requestedAt,
        Instant canceledAt,
        Instant completedAt,
        Instant failedAt,
        Account account
) {

    static ExchangeResult of(Exchange exchange, ExchangeAccount account, String maskedAccountNumber) {
        return new ExchangeResult(exchange.getId(), exchange.getRequestedAmountHp(), exchange.getFeeRate(),
                exchange.getFeeAmountHp(), exchange.getPayoutAmountKrw(), exchange.getStatus(),
                ExchangeSettlementRound.dateOf(exchange.getPayoutScheduledAt()), exchange.getRequestedAt(),
                exchange.getCanceledAt(), exchange.getCompletedAt(), exchange.getFailedAt(),
                new Account(account.getBankCode(), maskedAccountNumber, account.getAccountHolderName(),
                        account.getVerificationStatus()));
    }

    // 신청 당시 계좌 스냅샷
    public record Account(String bankCode, String accountNumberMasked, String accountHolderName,
            AccountVerificationStatus verificationStatus) {
    }
}
