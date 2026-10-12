package com.hapbang.billing.in;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hapbang.billing.domain.BillingException;

// Billing 컨트롤러의 오류만 RFC 9457 Problem Detail로 바꾸고, code 속성에 BillingErrorCode 이름을 담는다.
// TODO: 팀 공통 응답·예외 포맷이 정해지면 global 모듈로 옮기거나 맞춘다.
@RestControllerAdvice(basePackages = "com.hapbang.billing")
public class BillingExceptionHandler extends ResponseEntityExceptionHandler {

    // Billing 업무 예외를 오류 코드의 HTTP 상태·메시지·코드 이름을 담은 Problem Detail로 바꾼다.
    @ExceptionHandler(BillingException.class)
    public ProblemDetail handleBillingException(BillingException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(exception.getErrorCode().getStatus(),
                exception.getMessage());
        problemDetail.setProperty("code", exception.getErrorCode().name());
        return problemDetail;
    }
}
