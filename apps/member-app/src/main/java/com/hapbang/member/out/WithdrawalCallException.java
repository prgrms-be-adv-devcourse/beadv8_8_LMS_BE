package com.hapbang.member.out;

/**
 * 탈퇴 확인 중 다른 앱(지갑·출연·강의) 내부 API 호출이 답을 주지 못했다 (#593, docs/api/internal-withdrawals.md 5장).
 * 지갑 Client의 실패 분류(RETRYABLE·UNKNOWN_RESULT, internal-billing.md 2장)와 같은 생각으로 둘로 나눈다
 */
public class WithdrawalCallException extends RuntimeException {

    public enum Kind {
        /** 상대가 요청을 처리하지 않은 것이 확실하다 (연결 거부, 회로 열림 등). 되돌릴 것이 없다 */
        NOT_PROCESSED,
        /** 상대가 처리했을 수도 있다 (시간 초과, 5xx, 읽을 수 없는 응답). 지갑이면 닫혔을 수 있어 되돌려야 한다 */
        UNKNOWN_RESULT
    }

    private final Kind kind;

    public WithdrawalCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
