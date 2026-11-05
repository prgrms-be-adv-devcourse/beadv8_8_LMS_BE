package com.hapbang.lecture.global.exception;

/**
 * 잠시 뒤 다시 보내면 되는 거절을 나타내는 업무 예외. {@link GlobalExceptionHandler}가 {@code Retry-After} 헤더(초)를 붙여 응답하고,
 * 요청마다 로그를 남기지 않는다(남용·혼잡 때 몰리므로 거절은 지표와 거절한 곳의 로그로 본다).
 * 예: 회원별 요청 한도 초과(429, D80), 파일 업로드 자리 없음(503, D81)
 */
public interface RetryLater {

    /** 다시 보낼 수 있을 때까지 남은 초(1 이상) */
    long retryAfterSeconds();
}
