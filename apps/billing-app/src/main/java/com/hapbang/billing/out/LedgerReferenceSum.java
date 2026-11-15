package com.hapbang.billing.out;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.LedgerTransactionType;

// 한 지갑에서 참조 대상 ID·거래 유형·잔액 칸별로 원장 증감을 합한 조회 결과다.
public record LedgerReferenceSum(Long referenceId, LedgerTransactionType transactionType, BalanceBucket balanceBucket,
        Long amountHp) {
}
