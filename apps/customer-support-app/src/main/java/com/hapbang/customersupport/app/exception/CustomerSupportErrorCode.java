package com.hapbang.customersupport.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.customersupport.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 고객센터 오류 코드. 응답의 code는 enum 이름이다. global의 공통 핸들러가 ProblemDetail로 응답한다 */
@Getter
@RequiredArgsConstructor
public enum CustomerSupportErrorCode implements BaseErrorCode {
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."),
    INQUIRY_NOT_DELETABLE(HttpStatus.CONFLICT, "답변이 달린 문의는 삭제할 수 없습니다."),
    INQUIRY_REPLY_NOT_FOUND(HttpStatus.NOT_FOUND, "답변을 찾을 수 없습니다."),
    INQUIRY_TARGET_REQUIRED(HttpStatus.BAD_REQUEST, "강의 내용 다름 이의 제기는 주문 항목 ID가 필요합니다."),
    INQUIRY_TARGET_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "주문 항목 ID는 강의 내용 다름 이의 제기에만 보낼 수 있습니다."),
    NOT_OBJECTION_INQUIRY(HttpStatus.BAD_REQUEST, "강의 내용 다름 이의 제기 문의가 아닙니다."),
    OBJECTION_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 판단한 이의 제기입니다."),
    // 강의 연동(#278·#279) 전까지 강의 내용 다름 이의 제기의 접수·판단·철회를 막는다 (#433)
    OBJECTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "강의 내용 다름 이의 제기는 지금 접수·처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
