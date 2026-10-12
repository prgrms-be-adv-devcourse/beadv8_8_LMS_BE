package com.hapbang.appearance.domain;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 모듈 오류 코드. 응답의 {@code code} 값으로 그대로 나간다. 기능을 추가할 때 함께 늘려 간다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");

    private final HttpStatus status;
    private final String message;
}
