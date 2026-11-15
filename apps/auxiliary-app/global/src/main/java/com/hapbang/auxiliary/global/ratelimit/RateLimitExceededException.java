package com.hapbang.auxiliary.global.ratelimit;

import com.hapbang.auxiliary.global.exception.BusinessException;
import com.hapbang.auxiliary.global.exception.GlobalErrorCode;

import lombok.Getter;

/** 요청 한도 초과 (지갑 #627과 같음, #715). 공통 핸들러가 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After} 헤더로 응답한다 */
@Getter
public class RateLimitExceededException extends BusinessException {

    /** 다시 시도할 수 있을 때까지 남은 초(1 이상) */
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(GlobalErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
