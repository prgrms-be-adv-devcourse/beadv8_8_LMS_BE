package com.hapbang.member.domain;

import lombok.Getter;

/** 회원 모듈 예외 (MemberErrorCode를 담는다) */
@Getter
public class MemberException extends RuntimeException {

    private final MemberErrorCode errorCode;

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
