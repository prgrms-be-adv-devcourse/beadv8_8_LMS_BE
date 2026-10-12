package com.hapbang.billing.domain;

// 원장 항목의 발생 사유를 충전·보류·반환·지급·환불·환전·역분개로 구분한다.
public enum LedgerTransactionType {
    CHARGE,
    HOLD,
    RELEASE,
    PAYOUT,
    REFUND,
    EXCHANGE,
    REVERSAL
}
