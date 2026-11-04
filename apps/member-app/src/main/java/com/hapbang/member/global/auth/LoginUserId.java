package com.hapbang.member.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * 로그인 회원 ID를 컨트롤러 파라미터로 받는다 (예: @LoginUserId Long userId).
 * 로그인하지 않아도 부르는 공개 API는 {@code required = false}로 받으면 비로그인일 때 null이다
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(hidden = true)   // 로그인 정보에서 채우는 값이라 Swagger 입력 칸에서 숨긴다
public @interface LoginUserId {

    /** false면 비로그인일 때 401 대신 null을 넣는다 */
    boolean required() default true;
}
