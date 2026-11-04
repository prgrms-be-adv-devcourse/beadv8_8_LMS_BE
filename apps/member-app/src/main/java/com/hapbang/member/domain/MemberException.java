package com.hapbang.member.domain;

import com.hapbang.member.global.exception.BusinessException;

/** 회원 업무 예외. global의 공통 핸들러(GlobalExceptionHandler)가 ProblemDetail로 응답한다 */
public class MemberException extends BusinessException {

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode);
    }

    /** 원인 예외를 함께 남긴다 (로그에 원인 스택이 나온다) */
    public MemberException(MemberErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
