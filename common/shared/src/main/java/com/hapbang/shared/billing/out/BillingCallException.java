package com.hapbang.shared.billing.out;

/**
 * 지갑 내부 API의 결과를 받지 못했다 (D62, D64). 지갑이 응답한 실패는 예외가 아니라 {@link BillingCallResult.Failed}다.
 * shared는 앱에 의존하지 않아 업무 예외({@code BusinessException})가 아니다. 원인은 cause와 호출한 쪽 시도 기록에만 남긴다.
 */
public class BillingCallException extends RuntimeException {

    private final BillingCallFailureKind kind;
    private final int httpStatus;
    private final String rawCode;

    BillingCallException(BillingCallFailureKind kind, int httpStatus, String rawCode, String message,
                         Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.rawCode = rawCode;
    }

    public BillingCallFailureKind getKind() {
        return kind;
    }

    /** 지갑이 응답한 HTTP 상태. 응답을 받지 못했으면 0 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 지갑 오류 응답(ProblemDetail)의 {@code code}. 본문이 없으면 {@code null} */
    public String getRawCode() {
        return rawCode;
    }
}
