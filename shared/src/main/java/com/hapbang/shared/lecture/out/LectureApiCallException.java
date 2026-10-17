package com.hapbang.shared.lecture.out;

/**
 * 강의 내부 API 호출 실패 (D62). shared는 global에 의존하지 않아 업무 예외({@code BusinessException})가 아니다.
 * 호출한 모듈이 사유를 보고 자기 오류로 바꾼다.
 */
public class LectureApiCallException extends RuntimeException {

    public enum Reason {
        /** 강의가 요청을 거절했다(4xx: 잘못된 요청 값, 내부 API 키 오류 등). 같은 요청을 다시 보내도 같다 */
        REJECTED,
        /** 연결 실패·응답 시간 초과·5xx로 결과를 받지 못했다. 다시 시도할 수 있다(조회라 다시 보내도 안전하다) */
        UNAVAILABLE
    }

    private final Reason reason;
    private final int status;
    private final String code;

    LectureApiCallException(Reason reason, int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
        this.code = code;
    }

    public Reason getReason() {
        return reason;
    }

    /** HTTP 상태. 응답을 받지 못했으면 0 */
    public int getStatus() {
        return status;
    }

    /** 강의 오류 응답(ProblemDetail)의 {@code code}. 본문이 없으면(401 등) {@code null} */
    public String getCode() {
        return code;
    }
}
