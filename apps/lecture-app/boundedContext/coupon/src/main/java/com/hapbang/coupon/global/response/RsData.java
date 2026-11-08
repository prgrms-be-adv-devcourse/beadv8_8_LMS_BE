package com.hapbang.coupon.global.response;

import org.springframework.http.HttpStatus;

/**
 * 쿠폰 성공 응답 본문. 강의 앱의 성공 응답(팀 규칙 RsData)과 같은 모양이다. 쿠폰은 강의의 global을 쓰지 않아 같은 필드로 따로 둔다.
 * 오류는 이 형식이 아니라 ProblemDetail로 응답한다({@code CouponExceptionHandler}).
 * <p>
 * 컨트롤러는 {@code RsData}를 그대로 반환한다. 200이 아니면 HTTP 응답 상태를 {@code @ResponseStatus}로 같은 값으로 정한다
 * (예: 생성은 {@code @ResponseStatus(HttpStatus.CREATED)} + {@code RsData.created(...)}, 접수는 {@code HttpStatus.ACCEPTED} +
 * {@code RsData.of(HttpStatus.ACCEPTED, ...)}). 본문이 없는 응답(204)은 {@code ResponseEntity<Void>}를 쓴다.
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
