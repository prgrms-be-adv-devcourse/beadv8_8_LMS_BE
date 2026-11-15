package com.hapbang.auxiliary.search.app.exception;

import com.hapbang.auxiliary.global.exception.BusinessException;

/** 검색 모듈의 예상된 업무 실패(#771) */
public class SearchException extends BusinessException {

    public SearchException(SearchErrorCode errorCode) {
        super(errorCode);
    }

    /** 같은 코드에 상황에 맞는 메시지(예: 대상마다 고를 수 있는 정렬이 다름) */
    public SearchException(SearchErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public SearchException(SearchErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
