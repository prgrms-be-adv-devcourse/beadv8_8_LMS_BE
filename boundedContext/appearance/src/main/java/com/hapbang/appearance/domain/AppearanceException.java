package com.hapbang.appearance.domain;

import lombok.Getter;

/**
 * 출연 모듈의 업무 예외. {@code AppearanceExceptionHandler}가 Problem Detail로 바꿔 응답한다.
 */
// TODO: 팀 공통 커스텀 예외가 정해지면 그 구조에 맞춘다.
@Getter
public class AppearanceException extends RuntimeException {

    private final AppearanceErrorCode errorCode;

    public AppearanceException(AppearanceErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AppearanceException(AppearanceErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
