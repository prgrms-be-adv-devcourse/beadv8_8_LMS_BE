package com.hapbang.member.out;

/**
 * 탈퇴 때 지갑을 닫고 다시 여는 창구 (#593, docs/api/internal-withdrawals.md 4장). 돈이 드나드는 유일한 문이라 지갑만 닫는다.
 * 같은 withdrawalId로 다시 불러도 결과가 같다(멱등). 구현은 지갑이 shared Client 메서드를 만든 뒤 연결 코드로 더한다
 */
public interface WalletWithdrawalGate {

    /**
     * 지갑에서 한 트랜잭션으로 잠그고 → 막힌 이유를 확인하고 → 이유가 없으면 닫는다
     *
     * @throws WithdrawalCallException 답을 받지 못했을 때 ({@code UNKNOWN_RESULT}면 닫혔을 수 있다)
     */
    WalletCloseResult close(long withdrawalId, long userId);

    /**
     * 이 withdrawalId로 닫은 지갑을 다시 연다 (보상·복구). 닫힌 적이 없으면 아무것도 하지 않는다
     *
     * @throws WithdrawalCallException 답을 받지 못했을 때
     */
    void reopen(long withdrawalId, long userId);
}
