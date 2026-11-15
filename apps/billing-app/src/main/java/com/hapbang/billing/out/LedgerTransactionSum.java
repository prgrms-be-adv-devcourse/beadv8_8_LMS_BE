package com.hapbang.billing.out;

import com.hapbang.billing.domain.LedgerTransactionType;

// 한 지갑·HP 종류·잔액 칸의 원장 증감을 거래 유형별로 합한 조회 결과다.
public record LedgerTransactionSum(LedgerTransactionType transactionType, Long amountHp) {
}
