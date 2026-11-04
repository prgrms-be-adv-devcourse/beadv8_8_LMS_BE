package com.hapbang.billing.app;

import java.time.Instant;

import com.hapbang.billing.out.FeeIncomeRow;

// 관리자용 환전 수수료 수입 내역 한 건 (#336). 신청 금액 = 송금액(KRW) + 수수료다.
public record FeeIncomeResult(
        Long ledgerId,
        Long exchangeId,
        long feeAmountHp,
        long requestedAmountHp,
        long payoutAmountKrw,
        Instant recordedAt
) {

    static FeeIncomeResult from(FeeIncomeRow row) {
        return new FeeIncomeResult(row.ledgerId(), row.exchangeId(), row.feeAmountHp(), row.requestedAmountHp(),
                row.payoutAmountKrw(), row.recordedAt());
    }
}
