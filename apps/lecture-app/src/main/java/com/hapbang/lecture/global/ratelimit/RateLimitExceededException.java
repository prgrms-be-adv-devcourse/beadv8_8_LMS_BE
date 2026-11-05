package com.hapbang.lecture.global.ratelimit;

import com.hapbang.lecture.global.exception.BusinessException;
import com.hapbang.lecture.global.exception.GlobalErrorCode;
import com.hapbang.lecture.global.exception.RetryLater;

/** 회원별 요청 한도 초과 (D80). 공통 핸들러가 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After} 헤더로 응답한다 */
public class RateLimitExceededException extends BusinessException implements RetryLater {

    private final long retryAfterSeconds;

    /** @param retryAfterSeconds 다시 보낼 수 있을 때까지 남은 초(1 이상) */
    public RateLimitExceededException(long retryAfterSeconds) {
        super(GlobalErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
