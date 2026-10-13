package com.hapbang.lecture.domain;

import com.hapbang.global.exception.BusinessException;

/** 강의 업무 예외. global의 공통 핸들러(GlobalExceptionHandler)가 ProblemDetail로 응답한다 */
public class LectureException extends BusinessException {

    public LectureException(LectureErrorCode errorCode) {
        super(errorCode);
    }

    /** 같은 오류 코드에서 상황별로 메시지만 바꾼다 */
    public LectureException(LectureErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
