package com.hapbang.auxiliary.search.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.auxiliary.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 검색 모듈 오류 코드(#771). 응답 형식은 global {@code GlobalExceptionHandler}가 만든다(루트 AGENTS 7장) */
@Getter
@RequiredArgsConstructor
public enum SearchErrorCode implements BaseErrorCode {

    /** 고를 수 있는 정렬이 아니다. 기본 메시지는 인플루언서 것이고, 강의는 상세 메시지로 자기 정렬을 알린다(#883) */
    UNSUPPORTED_SORT(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬입니다. followers,desc 또는 rating,desc만 쓸 수 있습니다."),
    /** 앞에서 1,000건 밖의 페이지 */
    PAGE_TOO_DEEP(HttpStatus.BAD_REQUEST, "검색 결과는 앞에서 1,000건까지만 볼 수 있습니다. 검색어나 조건을 좁혀 주세요."),
    /** Elasticsearch에 닿지 못했거나 거절당했다 */
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 검색할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
