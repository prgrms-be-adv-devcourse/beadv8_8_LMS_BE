package com.hapbang.auxiliary.notification.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.auxiliary.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 알림 모듈 오류 코드. 응답 형식은 global {@code GlobalExceptionHandler}가 만든다(루트 AGENTS 7장) */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {

    /** 회원 이벤트의 회원 정보가 복제할 수 없는 값이다(ID·닉네임·역할·상태 없음) */
    INVALID_MEMBER_EVENT(HttpStatus.BAD_REQUEST, "복제할 수 없는 회원 정보입니다."),
    /** 남의·없는·밀려난(Soft Delete된) 알림. 응답으로 다른 회원 알림의 존재를 구분할 수 없게 하나로 쓴다(ND-052) */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    /** 받는 사람의 회원 복제본이 아직 없다. 이벤트 처리를 실패시켜 재시도 뒤 DLT로 보낸다(ND-035) */
    RECEIVER_NOT_REPLICATED(HttpStatus.CONFLICT, "받는 사람의 회원 복제본이 없습니다.");

    private final HttpStatus status;
    private final String message;
}
