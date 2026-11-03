package com.hapbang.member.domain;

/**
 * 탈퇴 작업 상태 (#593). 회원 상태({@code UserStatus})와 별개로 회원 앱 안에서만 쓴다.
 * <pre>
 * CHECKING ─막힘──────────────→ BLOCKED (끝, 회원이 정리할 것이 있음)
 *    ├─지갑 닫힘→ WALLET_CLOSED ─회원 저장→ COMPLETED (끝)
 *    │                └─실패→ 지갑 다시 열기 → ROLLED_BACK (끝, 시스템 실패로 되돌림)
 *    └─지갑 결과 불명·다시 열기 실패→ NEEDS_RECOVERY ─배치가 다시 열기→ ROLLED_BACK
 * </pre>
 */
public enum WithdrawalStatus {
    /** 출연·강의·지갑에 확인하는 중 */
    CHECKING,
    /** 지갑을 닫았고 회원 저장 전 */
    WALLET_CLOSED,
    /** 탈퇴 완료 */
    COMPLETED,
    /** 남은 돈·거래가 있어 막힘. 회원이 정리하고 다시 시도한다 (409) */
    BLOCKED,
    /** 시스템 실패로 되돌림. 회원은 그대로 다시 시도한다 (503) */
    ROLLED_BACK,
    /** 지갑을 닫았는지 모르거나 다시 열기가 실패함. 배치가 다시 열기를 다시 부른다 */
    NEEDS_RECOVERY;

    /** 끝난 상태인지 (더 바뀌지 않는다) */
    public boolean isFinished() {
        return this == COMPLETED || this == BLOCKED || this == ROLLED_BACK;
    }
}
