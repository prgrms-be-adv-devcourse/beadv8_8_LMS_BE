package com.hapbang.shared.billing.domain;

/**
 * 탈퇴용 지갑 닫기의 결과 (#623, docs/api/internal-withdrawals.md 4.1).
 */
public enum WithdrawalCloseOutcome {

    /** 막힌 이유가 없어 지갑을 닫았다. 닫힌 지갑은 충전·보류·환불·환전 신청을 거절한다 */
    CLOSED,

    /** 막힌 이유가 있어 지갑을 닫지 않았다. 이유는 reasons에 있다 */
    BLOCKED
}
