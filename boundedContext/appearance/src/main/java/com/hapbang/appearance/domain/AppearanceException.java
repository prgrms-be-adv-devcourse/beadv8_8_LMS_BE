package com.hapbang.appearance.domain;

import com.hapbang.global.exception.BusinessException;

/** 출연 업무 예외. global의 공통 핸들러(GlobalExceptionHandler)가 ProblemDetail로 응답한다 */
public class AppearanceException extends BusinessException {

    public AppearanceException(AppearanceErrorCode errorCode) {
        super(errorCode);
    }

    public AppearanceException(AppearanceErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    /** 출연 예외는 항상 출연 오류 코드로 만들므로 그 타입으로 돌려준다 (오류 코드별 분기용) */
    @Override
    public AppearanceErrorCode getErrorCode() {
        return (AppearanceErrorCode) super.getErrorCode();
    }
}
