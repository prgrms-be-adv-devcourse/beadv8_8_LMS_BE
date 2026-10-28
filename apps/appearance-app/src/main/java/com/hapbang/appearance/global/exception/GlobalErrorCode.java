package com.hapbang.appearance.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 모듈에 속하지 않는 공통 오류 코드. 공통 핸들러가 Spring·Security 예외(검증 실패, 권한 거절, 처리하지 못한 예외 등)를 응답할 때 쓴다.
 * 모듈의 업무 오류는 각 모듈의 오류 코드 enum으로 정의한다.
 */
@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements BaseErrorCode {

    /** 요청 값 검증·형식 오류. 응답의 errors에 항목별 메시지를 담는다 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "요청이 현재 상태와 맞지 않습니다."),
    /** 처리하지 못한 예외. 내부 메시지는 응답에 노출하지 않는다 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
