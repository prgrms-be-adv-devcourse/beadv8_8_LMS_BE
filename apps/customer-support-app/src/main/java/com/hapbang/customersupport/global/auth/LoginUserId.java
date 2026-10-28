package com.hapbang.customersupport.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;

/** 로그인 회원 ID를 컨트롤러 파라미터로 받는다 (예: @LoginUserId Long userId) */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(hidden = true)   // 세션에서 채우는 값이라 Swagger 입력 칸에서 숨긴다
public @interface LoginUserId {
}
