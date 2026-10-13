package com.hapbang.billing.domain;

import com.hapbang.global.exception.BusinessException;

/** 지갑 업무 예외. global의 공통 핸들러(GlobalExceptionHandler)가 ProblemDetail로 응답한다 */
public class BillingException extends BusinessException {

    public BillingException(BillingErrorCode errorCode) {
        super(errorCode);
    }

    public BillingException(BillingErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
