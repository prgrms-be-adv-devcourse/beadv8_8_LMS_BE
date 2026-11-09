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
    /** 알림 회원 복제본이 아직 없다(가입 이벤트가 늦게 옴, ND-035) */
    NOTIFICATION_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 회원 복제본이 없습니다.");

    private final HttpStatus status;
    private final String message;
}
