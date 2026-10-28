package com.hapbang.appearance.global.response;

import org.springframework.http.HttpStatus;

/**
 * 성공 응답 본문 (2026-10-13 팀 규칙). 오류는 이 형식이 아니라 공통 핸들러의 ProblemDetail로 응답한다.
 * <p>
 * 컨트롤러는 {@code RsData}를 그대로 반환한다. 200이 아니면 HTTP 응답 상태를 {@code @ResponseStatus}로 같은 값으로 정한다
 * (예: 생성은 {@code @ResponseStatus(HttpStatus.CREATED)} + {@code RsData.created(...)}).
 * 본문이 없는 응답(204)은 {@code ResponseEntity<Void>}를 쓴다.
 *
 * @param statusCode HTTP 상태 코드 (예: 200, 201)
 * @param message    처리 결과 메시지
 * @param data       응답 데이터
 */
public record RsData<T>(int statusCode, String message, T data) {

    public static <T> RsData<T> of(HttpStatus status, String message, T data) {
        return new RsData<>(status.value(), message, data);
    }

    /** 200 OK */
    public static <T> RsData<T> ok(String message, T data) {
        return of(HttpStatus.OK, message, data);
    }

    /** 201 Created */
    public static <T> RsData<T> created(String message, T data) {
        return of(HttpStatus.CREATED, message, data);
    }
}
