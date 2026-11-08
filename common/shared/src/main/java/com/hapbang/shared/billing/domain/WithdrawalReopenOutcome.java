package com.hapbang.shared.billing.domain;

/**
 * 탈퇴용 지갑 다시 열기의 결과 (#623, docs/api/internal-withdrawals.md 4.2). 어느 결과든 그 탈퇴 작업의 늦은 닫기는
 * {@code WITHDRAWAL_ALREADY_REOPENED}로 거절된다.
 */
public enum WithdrawalReopenOutcome {

    /** 이 탈퇴 작업으로 닫힌 지갑을 다시 열었다 */
    REOPENED,

    /** 이 탈퇴 작업으로 닫힌 적이 없다(닫기를 받지 못했거나 BLOCKED였거나 다른 탈퇴 작업으로 닫혔다). 지갑은 바꾸지 않았다 */
    NOT_CLOSED
}
