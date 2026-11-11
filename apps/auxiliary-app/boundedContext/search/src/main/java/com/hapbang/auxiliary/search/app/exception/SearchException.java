package com.hapbang.auxiliary.search.app.exception;

import com.hapbang.auxiliary.global.exception.BusinessException;

/** 검색 모듈의 예상된 업무 실패(#771) */
public class SearchException extends BusinessException {

    public SearchException(SearchErrorCode errorCode) {
        super(errorCode);
    }

    public SearchException(SearchErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
