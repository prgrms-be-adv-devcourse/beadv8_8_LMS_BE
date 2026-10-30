package com.hapbang.billing.out;

import java.time.Instant;

// 플랫폼 지갑의 환전 수수료 수입 원장 한 줄과 그 환전의 신청 금액·송금액 (#336). 환전은 Billing 테이블이라 원장과 조인해 읽는다.
public record FeeIncomeRow(
        Long ledgerId,
        Long exchangeId,
        Long feeAmountHp,
        Long requestedAmountHp,
        Long payoutAmountKrw,
        Instant recordedAt
) {
}
