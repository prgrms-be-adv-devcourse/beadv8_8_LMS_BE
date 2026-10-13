package com.hapbang.customersupport.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 고객센터 오류 코드. 응답의 code는 enum 이름이다. global의 공통 핸들러가 ProblemDetail로 응답한다 */
@Getter
@RequiredArgsConstructor
public enum CustomerSupportErrorCode implements BaseErrorCode {
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."),
    INQUIRY_NOT_DELETABLE(HttpStatus.CONFLICT, "답변이 달린 문의는 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
