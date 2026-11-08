package com.hapbang.coupon.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * 로그인 회원 ID를 쿠폰 컨트롤러 파라미터로 받는다 (예: {@code @LoginUserId Long memberId}). 다른 앱의 같은 이름 애노테이션과 같은
 * 동작이며, 쿠폰은 강의의 global을 쓰지 않아 따로 둔다. 값은 {@link LoginUserIdArgumentResolver}가 채운다.
 */
@Target(ElementType.PARAMETER)
@Parameter(hidden = true)   // 로그인 정보에서 채우는 값이라 Swagger 입력 칸에서 숨긴다
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}
