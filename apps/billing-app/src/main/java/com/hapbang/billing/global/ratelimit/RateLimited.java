package com.hapbang.billing.global.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드에 회원별 요청 한도를 건다 (#627, BD-140). {@link RateLimitInterceptor}가 컨트롤러를 부르기 전에 확인하므로
 * 한도를 넘은 요청은 상태를 바꾸지 않고 429 {@code TOO_MANY_REQUESTS}로 거절된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

    RateLimitedApi value();
}
