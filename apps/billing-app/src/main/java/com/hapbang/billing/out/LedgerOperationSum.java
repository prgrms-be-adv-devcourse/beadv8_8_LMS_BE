package com.hapbang.billing.out;

import java.time.Instant;

import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 한 지갑에서 업무 작업 하나(wallet_id + operation_key)에 속한 원장 줄을 HP 종류·잔액 버킷별로 합한 조회 결과다 (#295).
// 거래 유형·참조 대상·발생 시각은 한 작업 안에서 모두 같다(LedgerOperation이 작업 단위로 정한다).
public record LedgerOperationSum(
        Long firstLedgerId,
        String operationKey,
        LedgerTransactionType transactionType,
        BillingReferenceType referenceType,
        Long referenceId,
        Instant occurredAt,
        Long chargeAvailableHp,
        Long chargeHeldHp,
        Long revenueAvailableHp,
        Long revenueHeldHp
) {
}
