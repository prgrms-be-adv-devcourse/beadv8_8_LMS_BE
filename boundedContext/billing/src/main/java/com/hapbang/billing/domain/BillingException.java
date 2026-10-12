package com.hapbang.billing.domain;

import lombok.Getter;

// Billing 오류 코드를 담는 업무 예외이며, BillingExceptionHandler가 Problem Detail로 바꿔 응답한다.
// TODO: 팀 공통 커스텀 예외가 정해지면 그 구조에 맞춘다.
@Getter
public class BillingException extends RuntimeException {

    private final BillingErrorCode errorCode;

    // 오류 코드와 그 기본 메시지로 예외를 생성한다.
    public BillingException(BillingErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 오류 코드와 그 기본 메시지, 원인 예외로 예외를 생성한다.
    public BillingException(BillingErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
