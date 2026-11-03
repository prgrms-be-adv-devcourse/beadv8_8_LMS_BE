package com.hapbang.billing.global.ratelimit;

import com.hapbang.billing.global.exception.BusinessException;
import com.hapbang.billing.global.exception.GlobalErrorCode;

import lombok.Getter;

/** 회원별 요청 한도 초과 (#627). 공통 핸들러가 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After} 헤더로 응답한다 */
@Getter
public class RateLimitExceededException extends BusinessException {

    /** 다시 시도할 수 있을 때까지 남은 초(1 이상) */
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(GlobalErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
