package com.hapbang.member.in;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.hapbang.member.domain.MemberException;

/**
 * 회원 API 오류를 ProblemDetail(RFC 9457)로 응답한다.
 * basePackages로 회원 모듈 컨트롤러에만 적용하고, code에 MemberErrorCode 이름을 담는다.
 */
// TODO: 팀 공통 응답·예외 포맷이 정해지면 global 모듈로 옮기거나 맞춘다.
@RestControllerAdvice(basePackages = "com.hapbang.member")
public class MemberExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(MemberException.class)
    public ProblemDetail handleMemberException(MemberException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(exception.getErrorCode().getStatus(),
                exception.getMessage());
        problemDetail.setProperty("code", exception.getErrorCode().name());
        return problemDetail;
    }
}
