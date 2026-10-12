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

    // 출연 회원 (호스트·게스트 자격)
    APPEARANCE_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "출연 회원 정보를 찾을 수 없습니다."),
    APPEARANCE_USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "현재 이용할 수 없는 회원입니다."),
    NOT_INFLUENCER(HttpStatus.FORBIDDEN, "인플루언서 이상만 출연 거래에 참여할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
