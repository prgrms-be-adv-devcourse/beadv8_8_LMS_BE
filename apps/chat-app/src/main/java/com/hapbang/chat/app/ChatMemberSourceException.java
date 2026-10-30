package com.hapbang.chat.app;

/**
 * 회원 원본 스냅샷({@link ChatMemberSnapshotSource})을 읽지 못했다. 업무 오류가 아니라 외부 호출 실패라 {@code ChatException}이 아니다.
 * 백필이 사유를 보고 자기 오류 코드(MEMBER_SOURCE_REJECTED·MEMBER_SOURCE_UNAVAILABLE)로 바꾼다.
 */
public class ChatMemberSourceException extends RuntimeException {

    public enum Reason {
        /** 회원이 요청을 거절했다(4xx: 잘못된 요청 값, 내부 API 키 오류 등). 같은 요청을 다시 보내도 같다 */
        REJECTED,
        /** 연결 실패·응답 시간 초과·5xx·읽을 수 없는 응답으로 결과를 받지 못했다. 조회라 다시 시도해도 안전하다 */
        UNAVAILABLE
    }

    private final Reason reason;
    private final int status;
    private final String code;

    public ChatMemberSourceException(Reason reason, int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
        this.code = code;
    }

    public Reason getReason() {
        return reason;
    }

    /** HTTP 상태. 응답 상태를 알 수 없으면(연결 실패·시간 초과·성공 응답 본문을 읽지 못함) 0 */
    public int getStatus() {
        return status;
    }

    /** 회원 오류 응답(ProblemDetail)의 {@code code}. 본문이 없거나 읽지 못하면 {@code null} */
    public String getCode() {
        return code;
    }
}
